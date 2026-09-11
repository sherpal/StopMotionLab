package be.doeraene.services.movies

import be.doeraene.services.database.DatabaseService
import be.doeraene.utils.testshenanigans.HasTestPower
import castorwire.{Bridge, WireMessage}
import data.movie.Movie
import eventsourcing.{EventSourcingService, Time, cleanEventSourcingService, clearSupervisorMemory}
import io.circe.syntax.*

import java.nio.file.Paths
import scala.annotation.tailrec
import scala.concurrent.{Await, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class MoviesServiceTest extends munit.FunSuite with HasTestPower {

  val logActors: Boolean = false

  def fixture =
    FunFixture[(MoviesService, EventSourcingService, castor.Context.Test, DatabaseService)](
      setup = { test =>
        if logActors then println("---------------------------")
        given databaseService: DatabaseService = DatabaseService(Paths.get("./test-data/movies"), inTest = true)
        given ac: castor.Context.Test          = new castor.Context.Test() {
          override def reportRun(a: castor.Actor[?], msg: Any, token: castor.Context.Token): Unit = {
            if logActors then println(s"$a <- $msg")
            super.reportRun(a, msg, token)
          }
        }
        given eventSourcingService: EventSourcingService =
          EventSourcingService(
            EventSourcingService.Config.default,
            eventsourcing.SqlEventStore(databaseService.client),
            eventsourcing.CastorScheduler(),
            isInTest = true
          )
        (MoviesService(), eventSourcingService, ac, databaseService)
      },
      teardown = { (movies, eventSourcing, ac, db) =>
        ac.waitForInactivity()
        cleanEventSourcingService(eventSourcing, dbPath = None)
        be.doeraene.services.database.deleteDatabase(db)
      }
    )

  fixture.test("I can get a move, update its name, get it again") { (movies, _, _, _) =>
    for {
      newMovieId    <- movies.createF()
      maybeNewMovie <- movies.movieF(newMovieId)
      newMovie = maybeNewMovie.getOrElse(fail(s"Movie was not defined when returning from movieF call."))
      changed <- movies.updateNameF(newMovie.id, "new name")
      _ = assert(changed)
      maybeMovieAfter <- movies.movieF(newMovieId)
      movieAfter = maybeMovieAfter.getOrElse(fail(s"Movie after was not defined."))
      name       = movieAfter.name
      _          = assertEquals(name, "new name")
    } yield ()
  }

  fixture.test("I can create two movies and update the name of one of them") { (movies, eventSourcing, _, _) =>
    val id1 = movies.create()
    val id2 = movies.create()

    for (j <- 1 to 100) do movies.updateName(id1, newName = s"Iteration $j")

    clearSupervisorMemory(eventSourcing)

    movies.updateName(id1, newName = "hello there")
    val movie1 = movies.movie(id1).get
    val movie2 = movies.movie(id2).get

    assertEquals(movie1.name, "hello there")
    assertEquals(movie2.name, "Untitled")
  }

  fixture.test("I can delete a movie") { (movies, _, _, _) =>
    val id             = movies.create()
    val beforeDeleting = Time.now()
    movies.delete(id)
    val notMovie = movies.movie(id)
    assertEquals(notMovie, Option.empty)
    val rawMovie = movies.movieEvenNonExisting(id)
    assert(rawMovie.deleted)
    assert(rawMovie.deletedAt >= beforeDeleting)
  }

  fixture.test("Projection creates, updates and delete movies") { (movies, _, _, _) =>
    def pokeProjAndWait(): Unit = {
      movies.pokeProjection()
      @tailrec
      def waitUpToDate(): Unit = if !Await.result(movies.projectionHandle.isUpToDate, Duration.create("1 second"))
      then {
        Thread.`yield`()
        waitUpToDate()
      }
      waitUpToDate()
    }
    assertEquals(movies.moviesMetadata.length, 0)
    val id = movies.create()
    pokeProjAndWait()
    assertEquals(movies.moviesMetadata.length, 1)
    val movie = movies.movie(id).get
    movies.updateName(id, "new name")
    val moviesBeforeProjection = movies.moviesMetadata
    assertEquals(moviesBeforeProjection.length, 1)
    assertEquals(moviesBeforeProjection.head.name, "Untitled")
    pokeProjAndWait()
    val moviesAfterProjection = movies.moviesMetadata
    assertEquals(moviesAfterProjection.head.name, "new name")

    for (_ <- 1 to 10) do movies.create()
    pokeProjAndWait()

    val moviesAfterBulkCreate = movies.moviesMetadata
    assertEquals(moviesAfterBulkCreate.length, 11)

    movies.delete(moviesAfterBulkCreate(moviesAfterBulkCreate.length / 2).id)
    pokeProjAndWait()

    assertEquals(movies.moviesMetadata.length, 10)
  }

  fixture.test("Deleting projects a movie into the deleted-movies list, and restoring it removes it again") {
    (movies, _, _, _) =>
      def pokeAndWait(handle: eventsourcing.ProjectionRunner.ProjectionHandle): Unit = {
        handle.poke()
        @tailrec
        def waitUpToDate(): Unit = if !Await.result(handle.isUpToDate, Duration.create("1 second"))
        then {
          Thread.`yield`()
          waitUpToDate()
        }
        waitUpToDate()
      }
      // both projections react to the same events (Deleted / Restored), so both need to catch up before asserting.
      def pokeBothAndWait(): Unit = {
        pokeAndWait(movies.projectionHandle)
        pokeAndWait(movies.deletedMovieProjectionHandle)
      }

      val id = movies.create()
      pokeBothAndWait()
      assertEquals(movies.moviesMetadata.map(_.id), Vector(id))
      assertEquals(movies.deletedMoviesMetadata, Vector.empty)

      movies.delete(id)
      pokeBothAndWait()
      assertEquals(movies.moviesMetadata, Vector.empty, "gone from the active list")
      assertEquals(
        movies.deletedMoviesMetadata,
        Vector(data.movie.DeletedMovieMetadata(id, "Untitled")),
        "and tracked in the deleted list"
      )

      movies.restore(id)
      pokeBothAndWait()
      assertEquals(movies.moviesMetadata.map(_.id), Vector(id), "back in the active list")
      assertEquals(movies.deletedMoviesMetadata, Vector.empty, "and its tombstone is gone")
  }

  fixture.test("I can send a Movie command through the castorwire bridge and get the reply back") {
    (movies, _, ac, _) =>
      given castor.Context = ac

      val id = movies.create()

      // stand-in for "the frontend": a promise that resolves once the entity actually replies.
      val promise = Promise[Boolean]()
      val replyTo = new castor.SimpleActor[Boolean]() {
        def run(msg: Boolean): Unit = promise.trySuccess(msg)
      }

      // two bridges wired directly to each other, standing in for the two ends of one
      // websocket connection (see be.doeraene.routes.CommandRoutes for the real thing).
      var clientBridge: Bridge = null
      val serverBridge: Bridge = Bridge {
        case WireMessage.Reply(token, payload) => clientBridge.deliver(token, payload)
        case WireMessage.Subscribe(_, _, _)    => () // not tested here
        case WireMessage.UnSubscribe(_)        => () // not tested here
        case WireMessage.Command(_)            => () // the server never pushes commands to the client in this test
      }
      clientBridge = Bridge(_ => ()) // the client never gets sent a Command frame here either

      // the client encodes a real Movie.Command, containing a *real* local replyTo actor
      val commandJson =
        (Movie.Command.ChangeName("new name via wire", replyTo): Movie.Command)
          .asJson(using Movie.Command.codec(using clientBridge))

      // ... and only that JSON (plus the entity id) crosses into "the server", exactly as
      // be.doeraene.routes.CommandRoutes would receive it as a WireMessage.Command(frame).
      movies.commandRouter.dispatch(id.value, commandJson, serverBridge)

      val changed = Await.result(promise.future, Duration(2, "s"))
      assert(changed)
      assertEquals(movies.movie(id).map(_.name), Some("new name via wire"))
  }

}
