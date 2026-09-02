package be.doeraene.services.movies

import be.doeraene.services.database.DatabaseService
import eventsourcing.{EventSourcingService, cleanEventSourcingService, clearSupervisorMemory}

import java.nio.file.Paths
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global

class MoviesServiceTest extends munit.FunSuite {

  val logActors: Boolean = false

  def fixture =
    FunFixture[(MoviesService, EventSourcingService, castor.Context.Test)](
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
        (MoviesService(), eventSourcingService, ac)
      },
      teardown = { (movies, eventSourcing, ac) =>
        ac.waitForInactivity()
        cleanEventSourcingService(eventSourcing, "./test-data/movies")
      }
    )

  fixture.test("I can get a move, update its name, get it again") { (movies, _, _) =>
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

  fixture.test("I can create two movies and update the name of one of them") { (movies, eventSourcing, _) =>
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

  fixture.test("I can delete a movie") { (movies, _, _) =>
    val id             = movies.create()
    val beforeDeleting = System.currentTimeMillis()
    movies.delete(id)
    val notMovie = movies.movie(id)
    assertEquals(notMovie, Option.empty)
    val rawMovie = movies.movieEvenNonExisting(id)
    assert(rawMovie.deleted)
    assert(rawMovie.deletedAt >= beforeDeleting)
  }

}
