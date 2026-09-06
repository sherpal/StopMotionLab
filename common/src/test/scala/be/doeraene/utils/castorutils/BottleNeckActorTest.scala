package be.doeraene.utils.castorutils

import scala.concurrent.ExecutionContext
import scala.util.Success

class BottleNeckActorTest extends munit.FunSuite {

  /** Nothing runs until explicitly stepped -- lets each test fully control when async work actually happens, instead
    * of racing real thread scheduling to catch `BottleNeckActor` "mid-flight".
    */
  private class ManualExecutionContext extends ExecutionContext {
    private val pending = scala.collection.mutable.ArrayBuffer.empty[Runnable]

    def execute(runnable: Runnable): Unit     = pending.append(runnable)
    def reportFailure(cause: Throwable): Unit = throw cause

    def pendingCount: Int = pending.size

    /** Runs every pending task, including any freshly scheduled by earlier ones, until none are left. Only safe to
      * call when the pending work is actually bounded -- an unanswered [[BottleNeckActor.forcePass]] retries forever,
      * so tests exercising that use [[runSteps]] instead.
      */
    def runAll(): Unit = {
      var guard = 0
      while (pending.nonEmpty) {
        guard += 1
        assert(guard < 10_000, "runAll() did not settle -- possible infinite rescheduling loop")
        pending.remove(0).run()
      }
    }

    /** Runs up to `n` pending tasks (fewer if it runs out) -- a bounded alternative to [[runAll]] for driving a
      * retry loop that won't stop on its own until the test says so.
      */
    def runSteps(n: Int): Unit = {
      var i = 0
      while (i < n && pending.nonEmpty) {
        pending.remove(0).run()
        i += 1
      }
    }
  }

  private case class Cmd(value: Int, replyTo: castor.Actor[Int])

  /** A fake "entity" that never replies to a command on its own -- it just remembers the most recent one -- so a test
    * can hold [[BottleNeckActor]] "inflight" for exactly as long as it wants via [[release]], instead of racing a real
    * async reply.
    */
  private class ManualUnderlying(using castor.Context) extends castor.SimpleActor[Cmd] {
    private var awaitingReply: Option[Cmd] = None
    var receivedCount: Int                 = 0

    def run(msg: Cmd): Unit = {
      receivedCount += 1
      awaitingReply = Some(msg)
    }

    /** Answers whichever command is currently waiting, if any. */
    def release(): Unit = awaitingReply.foreach { msg =>
      awaitingReply = None
      msg.replyTo.send(msg.value * 2)
    }
  }

  private def fixture(): (ManualExecutionContext, ManualUnderlying, BottleNeckActor[Cmd]) = {
    val manualEC              = new ManualExecutionContext
    given ctx: castor.Context = castor.Context.Simple(manualEC, (t: Throwable) => t.printStackTrace())
    val underlying            = new ManualUnderlying
    val bottleNeck            = BottleNeckActor(underlying)
    (manualEC, underlying, bottleNeck)
  }

  test("a command reaches the entity and resolves once it replies") {
    val (manualEC, underlying, bottleNeck) = fixture()

    val result = bottleNeck.passIfPossible(Cmd(21, _))
    manualEC.runAll()
    assertEquals(result.value, None, "must still be waiting on the entity's reply")
    assertEquals(underlying.receivedCount, 1)

    underlying.release()
    manualEC.runAll()
    assertEquals(result.value, Some(Success(Some(42))))
  }

  test("a command sent while another is inflight is rejected with None, without ever reaching the entity") {
    val (manualEC, underlying, bottleNeck) = fixture()

    val first = bottleNeck.passIfPossible(Cmd(1, _))
    manualEC.runAll() // `first` reaches the entity and is now inflight, waiting on release()

    val second = bottleNeck.passIfPossible(Cmd(2, _))
    manualEC.runAll()

    assertEquals(second.value, Some(Success(None)))
    assertEquals(underlying.receivedCount, 1, "the second command must never have reached the entity")
    assertEquals(first.value, None, "rejecting the second command must not disturb the still-pending first one")

    underlying.release()
    manualEC.runAll()
    assertEquals(first.value, Some(Success(Some(2))))
  }

  test("the bottleneck accepts a new command again once the inflight one completes") {
    val (manualEC, underlying, bottleNeck) = fixture()

    val first = bottleNeck.passIfPossible(Cmd(1, _))
    manualEC.runAll()
    underlying.release()
    manualEC.runAll()
    assertEquals(first.value, Some(Success(Some(2))))

    val second = bottleNeck.passIfPossible(Cmd(3, _))
    manualEC.runAll()
    assertEquals(second.value, None, "the entity hasn't answered *this* command yet")
    assertEquals(underlying.receivedCount, 2)

    underlying.release()
    manualEC.runAll()
    assertEquals(second.value, Some(Success(Some(6))))
  }

  test("forcePass retries until the bottleneck frees up, instead of giving up on the first rejection") {
    val (manualEC, underlying, bottleNeck) = fixture()

    val first = bottleNeck.passIfPossible(Cmd(1, _))
    manualEC.runAll() // `first` is now inflight, waiting on release()

    val forced = bottleNeck.forcePass(Cmd(21, _))
    // Plenty of retries against the still-occupied bottleneck: none of them may reach the entity, and -- unlike
    // `passIfPossible` -- `forcePass` never gives up on its own here, so draining with an unbounded runAll() would
    // spin forever; a bounded runSteps() proves it keeps trying without waiting for that to happen.
    manualEC.runSteps(200)
    assertEquals(forced.value, None)
    assertEquals(underlying.receivedCount, 1, "none of forcePass's retries may have reached the entity yet")

    underlying.release() // frees the bottleneck for `first`'s reply
    manualEC.runAll()
    assertEquals(first.value, Some(Success(Some(2))))
    assertEquals(forced.value, None, "one retry got through and is itself now inflight, waiting on its own reply")
    assertEquals(underlying.receivedCount, 2)

    underlying.release() // now it's forced's own command being answered
    manualEC.runAll()
    assertEquals(forced.value, Some(Success(42)))
  }

  test("forcePass gives up after exhausting maxRetries if the bottleneck never frees up") {
    val (manualEC, underlying, bottleNeck) = fixture()

    val first = bottleNeck.passIfPossible(Cmd(1, _))
    manualEC.runAll() // `first` stays inflight forever in this test: release() is deliberately never called

    val forced = bottleNeck.forcePass(Cmd(21, _), maxRetries = 3)
    manualEC.runAll() // bounded this time: forcePass gives up after 3 retries, so this does settle

    forced.value match {
      case Some(scala.util.Failure(_)) => () // expected: out of retries
      case other                       => fail(s"expected forcePass to give up, got $other")
    }
  }

}
