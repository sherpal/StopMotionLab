package be.doeraene.services.movies

import be.doeraene.services.database.DatabaseService
import be.doeraene.utils.testshenanigans.HasTestPower
import eventsourcing.{EventSourcingService, cleanEventSourcingService, clearSupervisorMemory}

import java.nio.file.Paths
import scala.annotation.tailrec
import scala.concurrent.Await
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
          EventSourcingService(EventSourcingService.Config.default, databaseService.client, isInTest = true)
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
    val beforeDeleting = System.currentTimeMillis() / 1000
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

}
