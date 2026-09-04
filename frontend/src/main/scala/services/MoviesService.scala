package services

import data.movie.{Movie, MovieMetadata}
import io.circe.Codec
import urldsl.language.dummyErrorImpl.*
import utils.websocket.CommandBridgeClient

import scala.concurrent.{ExecutionContext, Future}

class MoviesService(using httpClient: HttpClient, commandBridge: CommandBridgeClient)(using ExecutionContext) {

  private val moviesPath = root / "movies"

  // the entity kind name is EntityInformation.entityKind.name server-side, i.e. the
  // runtime class name of Movie's state -- see MoviesService (server) / EntityInformation.
  private val movieEntityKind = "data.movie.Movie"

  def movies: Future[Vector[MovieMetadata]] = httpClient.get[Vector[MovieMetadata]](moviesPath / "all")

  def create(): Future[Movie.Id] = httpClient.post[Movie.Id](moviesPath / "create", ignore)(())

  def delete(id: Movie.Id): Future[Boolean] =
    httpClient.post[Boolean](moviesPath / "delete", param[Movie.Id]("movieId"))(id)

  /** Same effect as an HTTP call to `api/movies/update`, but sent as a real
    * `Movie.Command.ChangeName` over the shared castorwire connection instead -- see
    * `utils.websocket.CommandBridgeClient`.
    */
  def updateNameWs(id: Movie.Id, newName: String): Future[Boolean] = {
    given Codec[Movie.Command] = Movie.Command.codec(using commandBridge.bridge)
    commandBridge.ask[Movie.Command, Boolean](movieEntityKind, id.value)(Movie.Command.ChangeName(newName, _))
  }

}
