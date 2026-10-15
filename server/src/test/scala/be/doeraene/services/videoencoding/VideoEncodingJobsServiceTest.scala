package be.doeraene.services.videoencoding

import be.doeraene.services.database.DatabaseService
import be.doeraene.services.images.ImagesService
import be.doeraene.services.movies.MoviesService
import be.doeraene.services.videoencoding.VideoEncodingService.EncodingError
import be.doeraene.utils.castorutils.ask
import be.doeraene.utils.testshenanigans.HasTestPower
import data.images.ImageData
import data.movie.Movie
import data.video.VideoEncodingJob
import data.video.VideoEncodingJob.Status
import eventsourcing.{EventSourcingService, cleanEventSourcingService}

import java.awt.Color
import java.nio.file.Paths
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class VideoEncodingJobsServiceTest extends munit.FunSuite with HasTestPower {
  import VideoEncodingServiceTest.{decodeFrames, jpeg}

  private case class Fixture(
      jobs: VideoEncodingJobsService,
      movies: MoviesService,
      images: ImagesService,
      eventSourcing: EventSourcingService,
      db: DatabaseService,
      directory: os.Path
  )(using val castorContext: castor.Context.Test) {

    def addPicture(movieId: Movie.Id, bytes: Array[Byte]): Unit = {
      val image = images.storeAsNewImage(ImageData.MimeType.Jpeg, bytes)
      val added = Await.result(
        eventSourcing.entity(movieId.value, Movie.entityInfo).ask(Movie.Command.AddImage(image.id, _)),
        Duration.Inf
      )
      assert(added)
    }

    def movieWithPictures(colors: Color*): Movie.Id = {
      val movieId = movies.create()
      colors.foreach(color => addPicture(movieId, jpeg(64, 48)((_, _) => color)))
      movieId
    }

    @tailrec
    final def waitUntilFinished(jobId: VideoEncodingJob.Id, deadline: Long = System.nanoTime() + 60.seconds.toNanos)
        : Status =
      jobs.status(jobId) match {
        case Some(status) if status.isFinished => status
        case Some(_) if System.nanoTime() < deadline =>
          Thread.sleep(20)
          waitUntilFinished(jobId, deadline)
        case other => fail(s"Job $jobId did not finish in time, last status: $other")
      }
  }

  private val fixture = FunFixture[Fixture](
    setup = { _ =>
      given databaseService: DatabaseService = DatabaseService(Paths.get("./test-data/video-jobs"), inTest = true)
      given castor.Context.Test              = new castor.Context.Test()
      given eventSourcingService: EventSourcingService =
        EventSourcingService(
          EventSourcingService.Config.default,
          eventsourcing.SqlEventStore(databaseService.client),
          eventsourcing.CastorScheduler(),
          isInTest = true
        )
      given images: ImagesService = ImagesService()
      given movies: MoviesService = MoviesService()
      given VideoEncodingService  = VideoEncodingService()
      val directory               = os.temp.dir(prefix = "video-jobs-test")
      Fixture(VideoEncodingJobsService(directory), movies, images, eventSourcingService, databaseService, directory)
    },
    teardown = { fixture =>
      fixture.castorContext.waitForInactivity()
      cleanEventSourcingService(fixture.eventSourcing, dbPath = None)
      be.doeraene.services.database.deleteDatabase(fixture.db)
      os.remove.all(fixture.directory)
    }
  )

  fixture.test("A job encodes the movie in the background, then exposes the video") { f =>
    val movieId = f.movieWithPictures(Color.RED, Color.GREEN, Color.BLUE)

    val jobId = f.jobs.start(movieId, imagesPerSecond = 2).fold(error => fail(error.message), identity)

    assertEquals(f.waitUntilFinished(jobId), Status.Done)
    val video = f.jobs.video(jobId).getOrElse(fail("Video should be available once the job is done"))
    assertEquals(video.movieName, "Untitled")
    assertEquals(decodeFrames(video.file).frames.length, 3)
  }

  fixture.test("Starting a job refuses movies that can't be encoded") { f =>
    val emptyMovie = f.movies.create()
    assertEquals(f.jobs.start(emptyMovie, 12), Left(EncodingError.NoImages(emptyMovie)))
    assertEquals(f.jobs.start(Movie.Id(1234), 12), Left(EncodingError.MovieNotFound(Movie.Id(1234))))

    val movieId = f.movieWithPictures(Color.RED)
    assertEquals(f.jobs.start(movieId, 0), Left(EncodingError.InvalidImagesPerSecond(0)))
  }

  fixture.test("A failing encoding marks the job as failed, with the reason") { f =>
    val movieId = f.movieWithPictures(Color.RED)
    f.addPicture(movieId, Array[Byte](1, 2, 3)) // not a picture at all

    val jobId = f.jobs.start(movieId, 12).fold(error => fail(error.message), identity)

    f.waitUntilFinished(jobId) match {
      case Status.Failed(message) => assert(message.contains("picture #2"), message)
      case other                  => fail(s"Expected the job to fail, got $other")
    }
    assertEquals(f.jobs.video(jobId), None)
    assertEquals(os.list(f.directory), Seq.empty)
  }

  fixture.test("A new job for the same movie replaces the previous, finished, one") { f =>
    val movieId = f.movieWithPictures(Color.RED, Color.GREEN)

    val firstJob = f.jobs.start(movieId, 12).fold(error => fail(error.message), identity)
    assertEquals(f.waitUntilFinished(firstJob), Status.Done)

    val secondJob = f.jobs.start(movieId, 12).fold(error => fail(error.message), identity)
    assertEquals(f.jobs.status(firstJob), None)
    assertEquals(f.waitUntilFinished(secondJob), Status.Done)
    assertEquals(os.list(f.directory).map(_.last), Seq(s"${secondJob.uuidValue}.mp4"))
  }

  fixture.test("Finished jobs are forgotten after the retention period") { f =>
    val jobs =
      VideoEncodingJobsService(f.directory, retention = 0.seconds)(using VideoEncodingService()(using f.movies, f.images))
    val movieId = f.movieWithPictures(Color.RED)

    val jobId = jobs.start(movieId, 12).fold(error => fail(error.message), identity)
    @tailrec def waitUntilForgotten(attemptsLeft: Int): Unit =
      if jobs.status(jobId).isDefined && attemptsLeft > 0 then {
        Thread.sleep(20)
        waitUntilForgotten(attemptsLeft - 1)
      }
    waitUntilForgotten(3000)

    assertEquals(jobs.status(jobId), None)
    assertEquals(os.list(f.directory), Seq.empty)
  }

}
