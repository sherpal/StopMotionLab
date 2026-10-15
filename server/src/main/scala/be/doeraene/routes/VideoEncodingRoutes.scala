package be.doeraene.routes

import be.doeraene.services.videoencoding.VideoEncodingJobsService
import be.doeraene.services.videoencoding.VideoEncodingService.EncodingError
import data.movie.Movie
import data.video.VideoEncodingJob

import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

/** Encoding a movie into a video: start a job, poll its status until it's done, then download the video. See
  * [[VideoEncodingJobsService]] for why it's done this way.
  */
//noinspection TypeAnnotation
class VideoEncodingRoutes(using jobs: VideoEncodingJobsService)(using castor.Context, cask.util.Logger)
    extends cask.Routes
    with Helpers {

  @cask.post("api/movies/:movieId/video-jobs")
  def startJob(movieId: Int, imagesPerSecond: Int) =
    jobs.start(Movie.Id(movieId), imagesPerSecond) match {
      case Right(jobId) => json(jobId)
      case Left(error)  =>
        val statusCode = error match {
          case _: EncodingError.InvalidImagesPerSecond | _: EncodingError.NoImages => 400
          case _: EncodingError.MovieNotFound                                      => 404
        }
        cask.Response(error.message, statusCode = statusCode)
    }

  @cask.get("api/video-jobs/:jobId")
  def jobStatus(jobId: String) =
    parseJobId(jobId).flatMap(jobs.status) match {
      case Some(status) => json(status)
      case None         => cask.Response(s"Unknown video encoding job $jobId", statusCode = 404)
    }

  @cask.get("api/video-jobs/:jobId/video")
  def video(jobId: String) =
    parseJobId(jobId).flatMap(jobs.video) match {
      case Some(video) =>
        cask.Response[cask.Response.Data](
          fileContent(video.file),
          headers = Seq(
            "Content-Type"        -> "video/mp4",
            "Content-Disposition" -> contentDisposition(s"${video.movieName}.mp4")
          )
        )
      case None =>
        cask.Response[cask.Response.Data](s"No video (yet?) for job $jobId", statusCode = 404)
    }

  private def parseJobId(jobId: String): Option[VideoEncodingJob.Id] =
    Try(UUID.fromString(jobId)).toOption.map(VideoEncodingJob.Id.fromUUID)

  private def fileContent(file: os.Path): geny.Writable = new geny.Writable {
    override def contentLength: Option[Long] = Some(os.size(file))

    def writeBytesTo(out: OutputStream): Unit = os.read.stream(file).writeBytesTo(out)
  }

  /** Makes the browser download the response as a file with the given name (movie names can contain accents, hence
    * the RFC 6266 `filename*` with a plain ASCII `filename` fallback for older clients).
    */
  private def contentDisposition(fileName: String): String = {
    val asciiFallback = fileName.map(c => if c.isLetterOrDigit && c < 128 || " ._-".contains(c) then c else '_')
    val encoded       = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20")
    s"""attachment; filename="$asciiFallback"; filename*=UTF-8''$encoded"""
  }

  initialize()

}
