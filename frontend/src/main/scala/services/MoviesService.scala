package services

import be.doeraene.utils.castorutils.BottleNeckActor
import com.raquo.laminar.api.L.*
import data.movie.{DeletedMovieMetadata, Movie, MovieMetadata}
import io.circe.Codec
import urldsl.language.dummyErrorImpl.*
import utils.websocket.CommandBridgeClient

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

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
