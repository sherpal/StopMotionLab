package eventsourcing

import castor.SimpleActor
import scalasql.simple.{DbApi, SqliteDialect}

import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** Drives one [[Projection]]: polls `raw_event_envelope` for events of one entity kind past the projection's last saved
  * checkpoint, applies them in order, and persists progress as it goes.
  *
  * One `ProjectionRunner` is one castor actor, so a given projection's events are always applied strictly in order, one
  * at a time — independent projections run fully in parallel with each other and with the entity actors, since none of
  * them share a mailbox.
  *
  * @param autoPoll
  *   whether to keep re-scheduling itself after catching up (production). Tests should pass `false` and drive catch-up
  *   explicitly via `poke()`, since a self-rescheduling timer would otherwise keep `castor.Context.Test` permanently
  *   "active" and make `waitForInactivity()` hang forever.
  */
private[eventsourcing] class ProjectionRunner[Event, EntityState](
    db: DbApi,
    entityInfo: EntityInformation[?, Event, EntityState],
    projection: Projection[Event],
    pollInterval: FiniteDuration,
    autoPoll: Boolean
)(using castor.Context)
    extends castor.StateMachineActor[ProjectionRunner.Message] {
  import ProjectionRunner.*
  import SqliteDialect.*

  private def loadCheckpoint(): Int =
    db.run(ProjectionCheckpoint.select.filter(_.projectionName === projection.name))
      .toVector
      .headOption
      .map(_.lastOffset)
      .getOrElse(0)

  private def saveCheckpoint(offset: Int): Unit = {
    val exists = db.run(ProjectionCheckpoint.select.filter(_.projectionName === projection.name)).toVector.nonEmpty
    if exists then
      db.run(
        ProjectionCheckpoint
          .update(_.projectionName === projection.name)
          .set(_.lastOffset := offset, _.updatedAt := System.currentTimeMillis())
      )
    else
      db.run(
        ProjectionCheckpoint.insert.values(
          ProjectionCheckpoint(projection.name, offset, System.currentTimeMillis())
        )
      )
  }

  /** Runs the handler for one row, swallowing any exception (logging it) so a broken handler can never take the whole
    * actor down. Returns whether it succeeded.
    */
  private def runHandler(row: RawEventEnvelope): Boolean =
    try {
      projection.handle(row.entityId, entityInfo.decodeEnvelope(row))
      true
    } catch {
      case NonFatal(e) =>
        // TODO: route through a real logger instead, once one exists in this codebase.
        System.err.println(s"[projection ${projection.name}] handler failed for offset ${row.offset}: $e")
        e.printStackTrace()
        false
    }

  @annotation.tailrec
  private def processAtLeastOnce(rows: List[RawEventEnvelope], cursor: Int): Int = rows match {
    case row :: rest =>
      if runHandler(row) then {
        saveCheckpoint(row.offset)
        processAtLeastOnce(rest, row.offset)
      } else cursor // stop here: this event (and the rest of the batch) is retried on the next tick
    case Nil => cursor
  }

  @annotation.tailrec
  private def processAtMostOnce(rows: List[RawEventEnvelope], cursor: Int): Int = rows match {
    case row :: rest =>
      saveCheckpoint(row.offset) // commit *before* handling: a crash from here on just skips this event
      runHandler(row)            // best-effort; failure or not, we never come back to it
      processAtMostOnce(rest, row.offset)
    case Nil => cursor
  }

  private case class TheState(cursor: Int)
      extends State({
        case Tick() =>
          val batch = db
            .run(
              RawEventEnvelope.select
                .filter(e => e.offset > cursor && e.entityKind === entityInfo.entityKind.name)
                .sortBy(_.offset)
                .take(projection.batchSize)
            )
            .toVector

          val newCursor = projection.semantics match {
            case Projection.Semantics.AtLeastOnce => processAtLeastOnce(batch.toList, cursor)
            case Projection.Semantics.AtMostOnce  => processAtMostOnce(batch.toList, cursor)
          }

          if batch.sizeIs == projection.batchSize then {
            // the batch was full: there may be more waiting right behind it, so keep catching up
            // immediately instead of waiting out a full poll interval
            send(Tick())
          } else if autoPoll then {
            summon[castor.Context].scheduleMsg(
              this,
              Tick(),
              java.time.Duration.of(pollInterval.toMillis, ChronoUnit.MILLIS)
            )
          }
          // else: caught up, and auto-polling is off (test mode) — sit idle until `poke()` is called

          TheState(newCursor)
        case IsUpToDate(replyTo) =>
          val latestEnvelope = db
            .run(
              RawEventEnvelope.select
                .filter(e => e.entityKind === entityInfo.entityKind.name)
                .sortBy(_.offset)
                .desc
                .map(_.offset)
                .take(1)
            )
            .headOption
            .getOrElse(-1)
          replyTo.send(cursor >= latestEnvelope)
          state
      })

  override def initialState: State = TheState(loadCheckpoint())

  // Kick off the first catch-up/delivery cycle immediately, without waiting for a first poll tick.
  send(Tick())

  /** For tests: force one catch-up cycle, without waiting for the automatic poll timer. */
  private[eventsourcing] def poke(): Unit = send(Tick())
}

object ProjectionRunner {
  private[eventsourcing] sealed trait Message
  private[eventsourcing] case class Tick()                                     extends Message
  private[eventsourcing] case class IsUpToDate(replyTo: castor.Actor[Boolean]) extends Message

  /** Handle to a running projection. Only really useful in tests: `poke()` forces one catch-up cycle immediately,
    * instead of waiting for the automatic poll timer.
    */
  final class ProjectionHandle private[eventsourcing] (runner: ProjectionRunner[?, ?])(using castor.Context) {
    private[eventsourcing] def poke(): Unit = runner.poke()

    def isUpToDate: Future[Boolean] = {
      val promise    = scala.concurrent.Promise[Boolean]()
      val replyActor = new SimpleActor[Boolean]() {
        override def run(msg: Boolean): Unit = promise.success(msg)
      }
      runner.send(IsUpToDate(replyActor))
      promise.future
    }
  }

}
