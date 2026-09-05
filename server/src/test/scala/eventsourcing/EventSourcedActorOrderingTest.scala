package eventsourcing

import be.doeraene.utils.castorutils.ask
import io.circe.Codec

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

/** Regression test for a subtle mailbox-ordering hazard: when [[EventSourcedActor]] finishes an async step (the last,
  * empty recovery page; or a persisted effect completing) and re-`send()`s its buffered commands back to itself before
  * becoming `Loaded`, a command that happens to land in the *same* castor mailbox batch as that transition can be
  * applied *before* the re-sent ones — even though it logically arrived later. Counters/commutative events can't reveal
  * this (any interleaving yields the same total), so this test uses an order-sensitive entity (a log of tags) and
  * drives castor through a hand-steppable `ExecutionContext` to force the exact interleaving deterministically, instead
  * of hoping for a lucky race under real thread timing.
  */
class EventSourcedActorOrderingTest extends munit.FunSuite {

  /** Nothing runs until explicitly stepped — lets a test force one specific mailbox interleaving. */
  class ManualExecutionContext extends ExecutionContext {
    private val pending = scala.collection.mutable.ArrayBuffer.empty[Runnable]

    def execute(runnable: Runnable): Unit     = pending.append(runnable)
    def reportFailure(cause: Throwable): Unit = throw cause

    def pendingCount: Int = pending.size

    /** Runs the `i`-th still-pending task (0 = oldest), removing it first. */
    def runIndex(i: Int): Unit = pending.remove(i).run()

    /** Runs every pending task, including any freshly scheduled by earlier ones, until none are left. */
    def runAll(): Unit = {
      var guard = 0
      while (pending.nonEmpty) {
        guard += 1
        assert(guard < 10_000, "runAll() did not settle -- possible infinite rescheduling loop")
        runIndex(0)
      }
    }
  }

  /** Minimal in-memory [[EventStore]] whose futures are all already-completed (`Future.successful`), so that under a
    * [[ManualExecutionContext]] the only scheduling nondeterminism left is the one this test deliberately controls by
    * hand.
    */
  class InMemoryEventStore extends EventStore {
    private var envelopes: Vector[RawEventEnvelope] = Vector.empty
    private var checkpoints: Map[String, Int]       = Map.empty

    def appendEnvelopes(newEnvelopes: Vector[RawEventEnvelope]): Future[Unit] = {
      var nextOffset = envelopes.map(_.offset).maxOption.getOrElse(0)
      newEnvelopes.foreach { e =>
        nextOffset += 1
        envelopes :+= e.copy(offset = nextOffset)
      }
      Future.successful(())
    }

    def loadEnvelopes(
        entityKind: String,
        entityId: Int,
        afterSequenceNumber: Int,
        pageSize: Int
    ): Future[Vector[RawEventEnvelope]] =
      Future.successful(
        envelopes
          .filter(e => e.entityKind == entityKind && e.entityId == entityId && e.sequenceNumber > afterSequenceNumber)
          .sortBy(_.sequenceNumber)
          .take(pageSize)
      )

    def loadEnvelopesByOffset(entityKind: String, afterOffset: Int, pageSize: Int): Future[Vector[RawEventEnvelope]] =
      Future.successful(
        envelopes.filter(e => e.entityKind == entityKind && e.offset > afterOffset).sortBy(_.offset).take(pageSize)
      )

    def lastEntityId(entityKind: String): Future[Option[Int]] =
      Future.successful(envelopes.filter(_.entityKind == entityKind).map(_.entityId).maxOption)

    def latestOffset(entityKind: String): Future[Option[Int]] =
      Future.successful(envelopes.filter(_.entityKind == entityKind).map(_.offset).maxOption)

    def loadCheckpoint(projectionName: String): Future[Option[Int]] = Future.successful(checkpoints.get(projectionName))

    def saveCheckpoint(projectionName: String, offset: Int): Future[Unit] = {
      checkpoints += (projectionName -> offset)
      Future.successful(())
    }

    def close(): Unit = ()
  }

  object NoOpScheduler extends Scheduler {
    def scheduleOnce(delay: FiniteDuration)(action: () => Unit): Unit = ()
  }

  // An order-sensitive entity: a log of tags. Unlike a counter, appending "Q" then "R" produces a
  // different final value than appending "R" then "Q" -- that's what lets this test tell reordering
  // apart from correct behavior.
  object LogEntityDefs {
    case class Appended(tag: String) derives Codec
    sealed trait Command
    case class Append(tag: String)                           extends Command
    case class GetLog(replyTo: castor.Actor[Vector[String]]) extends Command

    val entityInfo: EntityInformation[Command, Appended, Vector[String]] =
      EntityInformation.usingCirceSerialization[Command, Appended, Vector[String]](
        Vector.empty,
        (event, state) => state :+ event.tag,
        (command, _, _) =>
          command match {
            case Append(tag)     => Effect.Persist(Appended(tag))
            case GetLog(replyTo) => Effect.ReplyTo(replyTo, identity)
          }
      )
  }

  test("a command landing in the same mailbox batch as the end of recovery is not applied out of order") {
    import LogEntityDefs.*

    val manualEC             = new ManualExecutionContext
    given ac: castor.Context = castor.Context.Simple(manualEC, (t: Throwable) => t.printStackTrace())

    val eventSourcing =
      EventSourcingService(EventSourcingService.Config.default, InMemoryEventStore(), NoOpScheduler, isInTest = true)

    val entity = eventSourcing.entity[Command, Appended, Vector[String]](1, entityInfo)

    entity.send(Append("R"))
    manualEC.runIndex(0) // Entity forwards to the Supervisor
    manualEC.runIndex(0) // Supervisor creates the actor, sends LoadNext() + Wrapper(Append("R"))
    manualEC.runIndex(0) // actor's first batch: LoadNext() kicks off recovery, Wrapper(R) gets buffered

    // Exactly one thing pending: the (empty, since nothing was persisted) recovery page's completion.
    assertEquals(manualEC.pendingCount, 1)

    entity.send(Append("Q"))
    // Force the exact interleaving: let the recovery's completion enqueue RecoveryPageLoaded and
    // schedule a fresh actor batch, *then* have Q's own Supervisor hop deliver Wrapper(Q) straight into
    // that same, not-yet-run batch -- landing right behind RecoveryPageLoaded in the actor's mailbox.
    manualEC.runIndex(0) // recovery completion -> actor.send(RecoveryPageLoaded(empty))
    manualEC.runIndex(0) // Entity forwards Q to the Supervisor
    manualEC.runIndex(1) // Supervisor forwards Wrapper(Q) straight to the actor, out of turn on purpose
    manualEC.runIndex(0) // the actor's next batch dequeues [RecoveryPageLoaded, Wrapper(Q)] together

    manualEC.runAll() // let R's buffered replay and both persistence callbacks settle

    val logF = entity.ask(GetLog.apply)
    manualEC.runAll()
    val log = Await.result(logF, 1.second)

    assertEquals(log, Vector("R", "Q"))
  }

}
