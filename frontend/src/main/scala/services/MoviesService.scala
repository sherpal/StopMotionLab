package services

import be.doeraene.utils.castorutils.BottleNeckActor
import com.raquo.laminar.api.L.*
import data.movie.{DeletedMovieMetadata, Movie, MovieMetadata}
import data.video.VideoEncodingJob
import io.circe.Codec
import urldsl.language.dummyErrorImpl.*
import utils.websocket.CommandBridgeClient

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

class MoviesService(using
    httpClient: HttpClient,
    commandBridge: CommandBridgeClient
)(using ExecutionContext, castor.Context) {

  private val moviesPath = root / "movies"

  // Movie.Command's replyTo fields need a live Bridge to become wire-safe -- see
  // castorwire.Bridge.bridgedCodec / Movie.Command.codec.
  private given Codec[Movie.Command] = Movie.Command.codec(using commandBridge.bridge)

  private val materializedEntityActors: mutable.Map[Movie.Id, BottleNeckActor[Movie.Command]] = mutable.Map.empty

  private def movieEntity(id: Movie.Id): BottleNeckActor[Movie.Command] =
    materializedEntityActors.getOrElseUpdate(
      id,
      BottleNeckActor(commandBridge.entity(id.value, Movie.entityInfo.entityKind))
    )

  def movies: Future[Vector[MovieMetadata]] = httpClient.get[Vector[MovieMetadata]](moviesPath / "all")

  def deletedMovies: Future[Vector[DeletedMovieMetadata]] =
    httpClient.get[Vector[DeletedMovieMetadata]](moviesPath / "deleted")

  def create(): Future[Movie.Id] = httpClient.post[Movie.Id](moviesPath / "create", ignore)(())

  private val videoJobPath = root / "video-jobs" / segment[VideoEncodingJob.Id]

  /** Asks the server to encode the movie into an mp4 video, in the background (see [[VideoEncodingJob]]). */
  def startVideoEncoding(id: Movie.Id, imagesPerSecond: Int): Future[VideoEncodingJob.Id] =
    httpClient.post[VideoEncodingJob.Id](
      moviesPath / segment[Movie.Id] / "video-jobs",
      param[Int]("imagesPerSecond")
    )(id, imagesPerSecond)

  def videoEncodingStatus(jobId: VideoEncodingJob.Id): Future[VideoEncodingJob.Status] =
    httpClient.get[VideoEncodingJob.Status](videoJobPath, ignore)(jobId, ())

  /** Polls the job's status until it's done (or failed, in which case the future fails with the reason). */
  def waitForVideo(
      jobId: VideoEncodingJob.Id,
      onProgress: VideoEncodingJob.Status => Unit,
      pollingInterval: FiniteDuration = 1.second
  ): Future[Unit] =
    videoEncodingStatus(jobId).flatMap {
      case VideoEncodingJob.Status.Done            => Future.unit
      case VideoEncodingJob.Status.Failed(message) => Future.failed(RuntimeException(message))
      case status                                  =>
        onProgress(status)
        val delay = Promise[Unit]()
        js.timers.setTimeout(pollingInterval)(delay.success(()))
        delay.future.flatMap(_ => waitForVideo(jobId, onProgress, pollingInterval))
    }

  /** Where to download the video, once its job is done. */
  def videoUrl(jobId: VideoEncodingJob.Id): String = httpClient.url(videoJobPath / "video")(jobId)

  def movieF(id: Movie.Id): Future[Option[Movie]] =
    movieEntity(id).forcePass(Movie.Command.Get.apply)

  def subscribe(id: Movie.Id): (updates: EventStream[Movie], cancellation: () => Unit) = {
    val (stream, cancel) = commandBridge.subscribe(id.value, Movie.entityInfo.entityKind)

    val initialBus = new EventBus[Movie]

    movieF(id).foreach(_.foreach(initialBus.writer.onNext))

    (EventStream.merge(stream, initialBus.events), cancel)
  }

  def subscribeToMoviesProjection(observer: Observer[Int]): Mod[HtmlElement] =
    commandBridge.subscribeToProjection("movie-projection", observer)

  def delete(id: Movie.Id): Future[Option[Boolean]] =
    movieEntity(id).passIfPossible(Movie.Command.Delete.apply)

  def deleteWithRetries(id: Movie.Id): Future[Boolean] =
    movieEntity(id).forcePass(Movie.Command.Delete.apply)

  def restore(id: Movie.Id): Future[Option[Boolean]] =
    movieEntity(id).passIfPossible(Movie.Command.Restore.apply)

  def restoreWithRetries(id: Movie.Id): Future[Boolean] =
    movieEntity(id).forcePass(Movie.Command.Restore.apply)

  def sendCommand[Reply](id: Movie.Id, command: castor.Actor[Reply] => Movie.Command): Future[Option[Reply]] =
    movieEntity(id).passIfPossible(command)

}
