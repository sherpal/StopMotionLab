package services

import be.doeraene.utils.castorutils.ask
import data.movie.{Movie, MovieMetadata}
import io.circe.Codec
import urldsl.language.dummyErrorImpl.*
import utils.websocket.CommandBridgeClient

import scala.concurrent.{ExecutionContext, Future}

class MoviesService(using
    httpClient: HttpClient,
    commandBridge: CommandBridgeClient
)(using ExecutionContext, castor.Context) {

  private val moviesPath = root / "movies"

  // Movie.Command's replyTo fields need a live Bridge to become wire-safe -- see
  // castorwire.Bridge.bridgedCodec / Movie.Command.codec.
  private given Codec[Movie.Command] = Movie.Command.codec(using commandBridge.bridge)

  // Opaque proxy for the real, server-side entity actor: sending it a command ships it
  // over the castorwire connection instead of an in-process castor.Actor.send, but from
  // here on it's an ordinary castor.Actor[Movie.Command] -- .ask works exactly as it does
  // server-side (see be.doeraene.services.movies.MoviesService).
  private def movieEntity(id: Movie.Id): castor.Actor[Movie.Command] =
    commandBridge.entity(id.value, Movie.entityInfo.entityKind)

  def movies: Future[Vector[MovieMetadata]] = httpClient.get[Vector[MovieMetadata]](moviesPath / "all")

  def create(): Future[Movie.Id] = httpClient.post[Movie.Id](moviesPath / "create", ignore)(())

  def delete(id: Movie.Id): Future[Boolean] =
    httpClient.post[Boolean](moviesPath / "delete", param[Movie.Id]("movieId"))(id)

  def movieF(id: Movie.Id): Future[Option[Movie]] = movieEntity(id).ask(Movie.Command.Get.apply)

  def updateNameWs(id: Movie.Id, newName: String): Future[Boolean] =
    movieEntity(id).ask(Movie.Command.ChangeName(newName, _))

  def deleteWs(id: Movie.Id): Future[Boolean] = movieEntity(id).ask(Movie.Command.Delete.apply)

}
