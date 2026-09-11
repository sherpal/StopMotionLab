package data.movie

import data.images.ImageData
import eventsourcing.{Effect, Time}

/** Pure tests of `Movie.Command.handle`/`Movie.Event.apply`: no actor system, event store or bridge involved -- both
  * are plain functions (`(Command, Movie, Int) => Effect[Event, Movie]` and `(Event, Movie) => Movie`), so a command
  * can be run against a hand-built `Movie` and checked directly, using [[interpret]] to play out the returned `Effect`
  * exactly the way `EventSourcedActor` would (persist first, then run any attached reply against the *post*-effect
  * state).
  */
class MovieCommandTest extends munit.FunSuite {

  private def interpret[E, S](effect: Effect[E, S], state: S)(applyEvent: (E, S) => S): S = effect match {
    case Effect.Persist(event)                    => applyEvent(event, state)
    case Effect.PersistMultiple(events)           => events.foldLeft(state)((s, e) => applyEvent(e, s))
    case Effect.WithSideEffect(inner, sideEffect) =>
      val newState = interpret(inner, state)(applyEvent)
      sideEffect(newState)
      newState
    case Effect.Ignore()                  => state
    case Effect.ReplyTo(replyTo, message) =>
      replyTo.send(message(state))
      state
  }

  /** Records every reply it's sent -- good enough here since nothing under test needs a live `castor.Context`. */
  private class RecordingActor[T] extends castor.Actor[T] {
    private val _received: scala.collection.mutable.ArrayBuffer[T] = scala.collection.mutable.ArrayBuffer.empty[T]

    def received: Vector[T] = _received.toVector

    def send(t: T)(using fileName: sourcecode.FileName, line: sourcecode.Line): Unit = _received += t

    def sendAsync(f: scala.concurrent.Future[T])(using fileName: sourcecode.FileName, line: sourcecode.Line): Unit = ()
  }

  private def run(command: Movie.Command, state: Movie, id: Int): Movie =
    interpret(command.handle(state, id), state)(_(_))

  private def run(command: Movie.Command, state: Movie): Movie = run(command, state, state.id.value)

  // Deterministic, not `ImageData.Id.random()`: that goes through `UUID.randomUUID()` -> `SecureRandom`, which the
  // JS platform of this cross-compiled module can't link (a pre-existing gap, unrelated to what's under test here).
  private def freshImage(index: Int): (ImageData.Id, Movie.ImageDataWithOrdering) = {
    val id = ImageData.Id.fromUUID(new java.util.UUID(0L, index.toLong))
    (id, Movie.ImageDataWithOrdering(ImageData(id), Some(index)))
  }

  private def activeMovie(images: Vector[Movie.ImageDataWithOrdering] = Vector.empty): Movie =
    Movie(Movie.Id(1), "reel", images, createdAt = Time.zero + 1L, deletedAt = Time.zero + 0L)

  // -- Create ---------------------------------------------------------------------------------------------------

  test("Create persists Created and replies with the new movie, the first time") {
    val replyTo = RecordingActor[Option[Movie]]()
    val fresh   = Movie.entityInfo.initialState

    val after = run(Movie.Command.Create(replyTo), fresh, id = 7)

    assertEquals(after.id, Movie.Id(7))
    assert(after.created)
    assertEquals(replyTo.received, Vector(Some(after)))
  }

  test("Create is a no-op, replying None, if the movie already exists") {
    val already = activeMovie()
    val replyTo = RecordingActor[Option[Movie]]()

    val after = run(Movie.Command.Create(replyTo), already)

    assertEquals(after, already)
    assertEquals(replyTo.received, Vector(None))
  }

  // -- ChangeName -------------------------------------------------------------------------------------------------

  test("ChangeName persists NameChanged and replies true, on an active movie") {
    val replyTo = RecordingActor[Boolean]()
    val after   = run(Movie.Command.ChangeName("Holiday reel", replyTo), activeMovie())

    assertEquals(after.name, "Holiday reel")
    assertEquals(replyTo.received, Vector(true))
  }

  test("ChangeName is rejected, replying false, on a movie that isn't active yet") {
    val notYetCreated = Movie.entityInfo.initialState
    val replyTo       = RecordingActor[Boolean]()

    val after = run(Movie.Command.ChangeName("Holiday reel", replyTo), notYetCreated)

    assertEquals(after, notYetCreated)
    assertEquals(replyTo.received, Vector(false))
  }

  test("ChangeName is rejected, replying false, on a deleted movie") {
    val deleted = activeMovie().copy(deletedAt = Time.zero + 2L)
    val replyTo = RecordingActor[Boolean]()

    val after = run(Movie.Command.ChangeName("Holiday reel", replyTo), deleted)

    assertEquals(after, deleted)
    assertEquals(replyTo.received, Vector(false))
  }

  // -- Get / RawGet -------------------------------------------------------------------------------------------------

  test("Get replies Some(movie) when active, None otherwise, and never persists anything") {
    val active  = activeMovie()
    val deleted = active.copy(deletedAt = Time.zero + 2L)

    val activeReply = RecordingActor[Option[Movie]]()
    assertEquals(run(Movie.Command.Get(activeReply), active), active)
    assertEquals(activeReply.received, Vector(Some(active)))

    val deletedReply = RecordingActor[Option[Movie]]()
    assertEquals(run(Movie.Command.Get(deletedReply), deleted), deleted)
    assertEquals(deletedReply.received, Vector(None))
  }

  test("RawGet always replies with the raw state, active or not") {
    val notYetCreated = Movie.entityInfo.initialState
    val replyTo       = RecordingActor[Movie]()

    run(Movie.Command.RawGet(replyTo), notYetCreated)

    assertEquals(replyTo.received, Vector(notYetCreated))
  }

  // -- Delete -------------------------------------------------------------------------------------------------------

  test("Delete persists Deleted and replies true, on an active movie") {
    val replyTo = RecordingActor[Boolean]()
    val after   = run(Movie.Command.Delete(replyTo), activeMovie())

    assert(after.deleted)
    assertEquals(replyTo.received, Vector(true))
  }

  test("Delete is rejected, replying false and changing nothing, on a movie that isn't active") {
    val notYetCreated = Movie.entityInfo.initialState
    val replyTo       = RecordingActor[Boolean]()

    val after = run(Movie.Command.Delete(replyTo), notYetCreated)

    assertEquals(after, notYetCreated)
    assertEquals(replyTo.received, Vector(false))
  }

  // -- Restore ----------------------------------------------------------------------------------------------------

  test("Restore persists Restored and replies true, on a deleted movie") {
    val deleted = activeMovie().copy(deletedAt = Time.zero + 2L)
    val replyTo = RecordingActor[Boolean]()

    val after = run(Movie.Command.Restore(replyTo), deleted)

    assert(!after.deleted)
    assert(after.active, "created and no longer deleted: active again")
    assertEquals(replyTo.received, Vector(true))
  }

  test("Restore is rejected, replying false and changing nothing, on a movie that isn't deleted") {
    val active  = activeMovie()
    val replyTo = RecordingActor[Boolean]()

    val after = run(Movie.Command.Restore(replyTo), active)

    assertEquals(after, active)
    assertEquals(replyTo.received, Vector(false))
  }

  test("Restore is rejected, replying false, on a movie that was never created") {
    val notYetCreated = Movie.entityInfo.initialState
    val replyTo       = RecordingActor[Boolean]()

    val after = run(Movie.Command.Restore(replyTo), notYetCreated)

    assertEquals(after, notYetCreated)
    assertEquals(replyTo.received, Vector(false))
  }

  test("Restore brings back the images and name the movie had when it was deleted") {
    val (id0, img0) = freshImage(0)
    val (id1, img1) = freshImage(1)
    val deleted     = activeMovie(Vector(img0, img1)).copy(name = "Holiday reel", deletedAt = Time.zero + 2L)
    val replyTo     = RecordingActor[Boolean]()

    val after = run(Movie.Command.Restore(replyTo), deleted)

    assertEquals(after.name, "Holiday reel")
    assert(after.containsImage(id0, 0))
    assert(after.containsImage(id1, 1))
  }

  test("Delete then Restore round-trips back to the exact same active state") {
    val movie        = activeMovie()
    val deleteReply  = RecordingActor[Boolean]()
    val deleted      = run(Movie.Command.Delete(deleteReply), movie)
    val restoreReply = RecordingActor[Boolean]()
    val restored     = run(Movie.Command.Restore(restoreReply), deleted)

    assertEquals(restored, movie, "deletedAt round-trips back to 0, the rest was never touched")
  }

  // -- RemoveImages -----------------------------------------------------------------------------------------------

  test("RemoveImages removes the given images and compacts the remaining indices") {
    val (id0, img0) = freshImage(0)
    val (id1, img1) = freshImage(1)
    val (id2, img2) = freshImage(2)
    val movie       = activeMovie(Vector(img0, img1, img2))
    val replyTo     = RecordingActor[Boolean]()

    val after = run(Movie.Command.RemoveImages(Vector(id0 -> 0), replyTo), movie)

    assertEquals(replyTo.received, Vector(true))
    assertEquals(after.images.map(_.maybeIndex), Vector(None, Some(0), Some(1)))
    assert(after.containsImage(id1, 0))
    assert(after.containsImage(id2, 1))
  }

  test("RemoveImages rejects the whole batch, changing nothing, if any pair doesn't match the current state") {
    val (id0, img0) = freshImage(0)
    val (id1, img1) = freshImage(1)
    val movie       = activeMovie(Vector(img0, img1))
    val replyTo     = RecordingActor[Boolean]()

    // id1 is really at index 1, not 0: the whole command must be rejected, not partially applied.
    val after = run(Movie.Command.RemoveImages(Vector(id0 -> 0, id1 -> 0), replyTo), movie)

    assertEquals(after, movie)
    assertEquals(replyTo.received, Vector(false))
  }

  test("RemoveImages is rejected on a movie that isn't active") {
    val (id0, img0)   = freshImage(0)
    val notYetCreated = Movie.entityInfo.initialState.copy(images = Vector(img0))
    val replyTo       = RecordingActor[Boolean]()

    val after = run(Movie.Command.RemoveImages(Vector(id0 -> 0), replyTo), notYetCreated)

    assertEquals(after, notYetCreated)
    assertEquals(replyTo.received, Vector(false))
  }

  // -- DuplicateImages --------------------------------------------------------------------------------------------

  test("DuplicateImages inserts a copy right after the original and reindexes the rest") {
    val (id0, img0) = freshImage(0)
    val (id1, img1) = freshImage(1)
    val movie       = activeMovie(Vector(img0, img1))
    val replyTo     = RecordingActor[Boolean]()

    val after = run(Movie.Command.DuplicateImages(Vector(id0 -> 0), replyTo), movie)

    assertEquals(replyTo.received, Vector(true))
    val ordered =
      after.images.collect { case Movie.ImageDataWithOrdering(image, Some(index)) => index -> image.id }.sortBy(_._1)
    assertEquals(ordered, Vector(0 -> id0, 1 -> id0, 2 -> id1))
  }

  test("DuplicateImages rejects the whole batch if any pair doesn't match the current state") {
    val (id0, img0) = freshImage(0)
    val movie       = activeMovie(Vector(img0))
    val replyTo     = RecordingActor[Boolean]()

    val after = run(Movie.Command.DuplicateImages(Vector(id0 -> 1), replyTo), movie) // id0 is at index 0, not 1

    assertEquals(after, movie)
    assertEquals(replyTo.received, Vector(false))
  }

  // -- MoveImageRange -----------------------------------------------------------------------------------------------

  test("MoveImageRange(Right, ...) shifts the range right, displacing the image right after it") {
    // Only two images -- nothing exists past `maxIndex + 1`, which sidesteps the missing-upper-bound bug documented
    // below and isolates the actually-correct part of this behaviour.
    val (id0, img0) = freshImage(0)
    val (id1, img1) = freshImage(1)
    val movie       = activeMovie(Vector(img0, img1))
    val replyTo     = RecordingActor[Boolean]()

    // move the single-image range [0, 0] to the right, past id1
    val after = run(Movie.Command.MoveImageRange(Movie.MoveDirection.Right, minIndex = 0, maxIndex = 0, replyTo), movie)

    assertEquals(replyTo.received, Vector(true))
    assert(after.containsImage(id1, 0))
    assert(after.containsImage(id0, 1))
  }

  test("MoveImageRange(Left, ...) shifts the range left, displacing the image right before it") {
    // minIndex = 1, so the displaced slot (minIndex - 1 = 0) is the lowest index there is -- which sidesteps the
    // missing-lower-bound bug documented below and isolates the actually-correct part of this behaviour.
    val (id0, img0) = freshImage(0)
    val (id1, img1) = freshImage(1)
    val (id2, img2) = freshImage(2)
    val movie       = activeMovie(Vector(img0, img1, img2))
    val replyTo     = RecordingActor[Boolean]()

    // move the single-image range [1, 1] to the left, past id0
    val after = run(Movie.Command.MoveImageRange(Movie.MoveDirection.Left, minIndex = 1, maxIndex = 1, replyTo), movie)

    assertEquals(replyTo.received, Vector(true))
    assert(after.containsImage(id1, 0))
    assert(after.containsImage(id0, 1))
    assert(after.containsImage(id2, 2), "outside the moved range: must be left untouched")
  }

  test("MoveImageRange(Right, ...) leaves an image well beyond the moved range untouched") {
    // Regression test for a fixed bug: `RangeMoved`'s Right branch in `Movie.Event.apply` used to only special-case
    // `index == maxIndex + 1` (displaced element) and `index < minIndex` (untouched); everything else, including
    // indices *well past* `maxIndex + 1`, fell through to the catch-all `Some(index + 1)` and got bumped along with
    // the moved range. It now also special-cases `index > maxIndex` (mirroring `Left`'s matching guard), so id2 here
    // -- well beyond the moved range -- correctly stays put instead of being dragged from 2 to 3.
    val (id0, img0) = freshImage(0)
    val (id1, img1) = freshImage(1)
    val (id2, img2) = freshImage(2)
    val movie       = activeMovie(Vector(img0, img1, img2))
    val replyTo     = RecordingActor[Boolean]()

    // move the single-image range [0, 0] to the right, past id1 -- id2 is well beyond the range and should be untouched
    val after = run(Movie.Command.MoveImageRange(Movie.MoveDirection.Right, minIndex = 0, maxIndex = 0, replyTo), movie)

    assertEquals(replyTo.received, Vector(true))
    assert(after.containsImage(id1, 0))
    assert(after.containsImage(id0, 1))
    assert(after.containsImage(id2, 2), "outside the moved range: must be left untouched")
  }

  test("MoveImageRange(Left, ...) leaves an image well below the moved range untouched") {
    // Regression test for the symmetric fixed bug: `Left`'s catch-all (`Some(index - 1)`) used to also swallow
    // indices *below* the displaced slot (`minIndex - 1`). It now special-cases `index < minIndex` (mirroring
    // `Right`'s matching guard), so id0 here -- well below the moved range -- correctly stays put instead of being
    // dragged from 0 to -1.
    val (id0, img0) = freshImage(0)
    val (id1, img1) = freshImage(1)
    val (id2, img2) = freshImage(2)
    val (id3, img3) = freshImage(3)
    val (id4, img4) = freshImage(4)
    val movie       = activeMovie(Vector(img0, img1, img2, img3, img4))
    val replyTo     = RecordingActor[Boolean]()

    // move the single-image range [2, 2] to the left, past id1 -- id0 is well below the range and should be untouched
    val after = run(Movie.Command.MoveImageRange(Movie.MoveDirection.Left, minIndex = 2, maxIndex = 2, replyTo), movie)

    assertEquals(replyTo.received, Vector(true))
    assert(after.containsImage(id1, 2))
    assert(after.containsImage(id2, 1))
    assert(after.containsImage(id3, 3))
    assert(after.containsImage(id4, 4))
    assert(after.containsImage(id0, 0), "outside the moved range: must be left untouched")
  }

  test("MoveImageRange is rejected when there is nothing beyond maxIndex to swap in") {
    val (_, img0) = freshImage(0)
    val (_, img1) = freshImage(1)
    val movie     = activeMovie(Vector(img0, img1))
    val replyTo   = RecordingActor[Boolean]()

    // [0,1] is the whole movie already: nothing exists past index 1 to displace.
    val after = run(Movie.Command.MoveImageRange(Movie.MoveDirection.Right, minIndex = 0, maxIndex = 1, replyTo), movie)

    assertEquals(after, movie)
    assertEquals(replyTo.received, Vector(false))
  }

  test("MoveImageRange(Left, minIndex = 0, ...) is correctly rejected: there is nothing at minIndex - 1 to displace") {
    // Regression test for a fixed bug: the acceptance guard in `Command.handle` used to check
    // `state.images.flatMap(_.maybeIndex).exists(_ > maxIndex)` regardless of `direction` -- correct for `Right`
    // (there must be something at maxIndex + 1 to displace), but wrong for `Left`, which actually needs something at
    // `minIndex - 1`. With minIndex = 0 there never is one, but the old guard didn't ask for it (it only checked for
    // images beyond maxIndex, which images 3 and 4 below happen to satisfy), so this used to be wrongly accepted and
    // corrupt image 0's index to -1. The guard now branches on `direction` (`minIndex > 0` for `Left`) and correctly
    // rejects this instead.
    val (id0, img0) = freshImage(0)
    val (id1, img1) = freshImage(1)
    val (id2, img2) = freshImage(2)
    val (id3, img3) = freshImage(3)
    val (id4, img4) = freshImage(4)
    val movie       = activeMovie(Vector(img0, img1, img2, img3, img4))
    val replyTo     = RecordingActor[Boolean]()

    val after = run(Movie.Command.MoveImageRange(Movie.MoveDirection.Left, minIndex = 0, maxIndex = 2, replyTo), movie)

    assertEquals(after, movie, "rejected: nothing should have changed")
    assertEquals(replyTo.received, Vector(false))
  }

}
