package be.doeraene.services.videoencoding

import be.doeraene.services.videoencoding.VideoEncodingService.EncodingError
import data.images.ImageData
import data.movie.Movie
import data.video.VideoEncodingJob
import data.video.VideoEncodingJob.Status

import java.time.Instant
import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

/** Runs [[VideoEncodingService]] in the background, so that clients don't have to keep an HTTP request open for the
  * minutes it can take: they start a job, poll its status, then download the video once it's done.
  *
  * Jobs only live in memory (a server restart forgets them, which is fine: they're just a cache of something that can
  * be recomputed), and run one at a time, so that two encodings never fight over CPU and memory.
  *
  * Videos are written in `directory`, which is emptied when the service starts. A finished job (and its video) is
  * forgotten after `retention`, or as soon as a new job is started for the same movie.
  */
class VideoEncodingJobsService(directory: os.Path, retention: FiniteDuration = 1.hour)(using
    videoEncodingService: VideoEncodingService
) {

  private case class Job(
      movieId: Movie.Id,
      movieName: String,
      sequenceNumber: Long,
      status: Status,
      finishedAt: Option[Instant]
  )

  /** All accesses to `jobs` and `nextSequenceNumber` must be synchronized on `this`. */
  private var jobs: Map[VideoEncodingJob.Id, Job] = Map.empty
  private var nextSequenceNumber: Long            = 0L

  private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable =>
    val thread = Thread(runnable, "video-encoding")
    // must not prevent the JVM from exiting (it's killed mid-encoding at shutdown, which is fine)
    thread.setDaemon(true)
    thread
  }

  if os.exists(directory) then os.remove.all(directory)
  os.makeDir.all(directory)

  private def videoFile(jobId: VideoEncodingJob.Id): os.Path = directory / s"${jobId.uuidValue}.mp4"

  /** Queues the encoding of the movie, as it is right now (pictures added afterwards won't be in the video). */
  def start(movieId: Movie.Id, imagesPerSecond: Int): Either[EncodingError, VideoEncodingJob.Id] =
    videoEncodingService.movieToEncode(movieId, imagesPerSecond).map { movie =>
      val jobId = VideoEncodingJob.Id.random()
      synchronized {
        forgetExpiredJobs()
        jobs.collect { case (id, job) if job.movieId == movieId && job.status.isFinished => id }.foreach(forget)
        jobs += jobId -> Job(movieId, movie.name, nextSequenceNumber, Status.Queued(0), None)
        nextSequenceNumber += 1
      }
      executor.execute(() => run(jobId, movie.sortedImages, imagesPerSecond))
      jobId
    }

  def status(jobId: VideoEncodingJob.Id): Option[Status] = synchronized {
    forgetExpiredJobs()
    jobs.get(jobId).map { job =>
      job.status match {
        case Status.Queued(_) =>
          val queuedBefore = jobs.values.count(other =>
            other.status.isInstanceOf[Status.Queued] && other.sequenceNumber < job.sequenceNumber
          )
          Status.Queued(queuedBefore + 1)
        case other => other
      }
    }
  }

  /** The video produced by the job, if it's done, along with the name of the movie it was made from. */
  def video(jobId: VideoEncodingJob.Id): Option[(file: os.Path, movieName: String)] = synchronized {
    jobs.get(jobId).collect { case job if job.status == Status.Done => (videoFile(jobId), job.movieName) }
  }

  private def run(jobId: VideoEncodingJob.Id, images: Vector[ImageData], imagesPerSecond: Int): Unit = {
    val totalFrames = images.length
    updateStatus(jobId, Status.Running(0, totalFrames))
    try {
      videoEncodingService.encodeImages(
        images,
        imagesPerSecond,
        videoFile(jobId),
        encodedFrames => updateStatus(jobId, Status.Running(encodedFrames, totalFrames))
      )
      updateStatus(jobId, Status.Done)
    } catch {
      // OutOfMemoryError included: the encoding's buffers become garbage right away, so the server can carry on
      case error @ (NonFatal(_) | _: OutOfMemoryError) =>
        error.printStackTrace()
        deleteVideo(jobId)
        updateStatus(jobId, Status.Failed(VideoEncodingJobsService.describe(error)))
    }
  }

  private def updateStatus(jobId: VideoEncodingJob.Id, status: Status): Unit = synchronized {
    jobs.get(jobId).foreach { job =>
      jobs += jobId -> job.copy(
        status = status,
        finishedAt = Option.when(status.isFinished)(Instant.now())
      )
    }
  }

  private def forgetExpiredJobs(): Unit = {
    val expiredBefore = Instant.now().minusMillis(retention.toMillis)
    jobs.collect { case (id, job) if job.finishedAt.exists(_.isBefore(expiredBefore)) => id }.foreach(forget)
  }

  private def forget(jobId: VideoEncodingJob.Id): Unit = {
    jobs -= jobId
    deleteVideo(jobId)
  }

  /** Best effort: on Windows, deleting a file fails while it's being downloaded. It'll be cleaned at next start-up. */
  private def deleteVideo(jobId: VideoEncodingJob.Id): Unit =
    try os.remove(videoFile(jobId))
    catch { case NonFatal(error) => System.err.println(s"Could not delete video of job $jobId: $error") }

}

object VideoEncodingJobsService {

  /** A human readable description of the error and its causes, e.g. "Could not read picture #3 of the movie: ...". */
  private def describe(error: Throwable): String =
    Iterator
      .iterate(error)(_.getCause)
      .takeWhile(_ != null)
      .take(5)
      .map(cause => Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName))
      .mkString(": ")

}
