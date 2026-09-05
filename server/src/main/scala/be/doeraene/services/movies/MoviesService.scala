package be.doeraene.services.movies

import be.doeraene.services.database.DatabaseService
import castor.SimpleActor
import castorwire.{Bridge, CommandRouter}
import data.movie.{Movie, MovieMetadata}
import eventsourcing.{Effect, EntityInformation, EventSourcingService, Projection, ProjectionRunner}
import be.doeraene.utils.castorutils.ask
import data.movie.Movie.Event
import be.doeraene.services.database.tables.Movie as DBMovie
import be.doeraene.utils.testshenanigans.OnlyInTest
import io.circe.Json
import scalasql.simple.SqliteDialect

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}

class MoviesService()(using db: DatabaseService, eventSourcing: EventSourcingService)(using castor.Context) {

  private def now(): Long = System.currentTimeMillis() / 1000

  private val entityInfo = Movie.entityInfo

  private def movieEntity(id: Movie.Id) = eventSourcing.entity(id.value, entityInfo)

  /** Exposes a subset of [[Movie.Command]] to castorwire-bridged connections (see `be.doeraene.routes.CommandRoutes`).
    * `Create` and `RawGet` are deliberately left out: `Create` needs the id-assignment retry loop in `createF`, and
    * `RawGet` is an internal escape hatch that ignores whether the movie is active/deleted -- neither should be
    * reachable directly by a client-chosen entity id.
    */
  val commandRouter: CommandRouter = new CommandRouter {
    def entityKind: String = entityInfo.entityKind.name

    def dispatch(entityId: Int, json: Json, bridge: Bridge): Unit =
      json.as[Movie.Command](using Movie.Command.codec(using bridge)) match {
        case Right(command) => movieEntity(Movie.Id(entityId)).send(command)
        case Left(err)      => System.err.println(s"Failed to decode Movie.Command: $err")
      }
  }

  private val movieProjection: Projection[Movie.Event] = Projection(
    "movie-projection",
    Projection.Semantics.AtLeastOnce
  ) { (id, envelope) =>
    import SqliteDialect.*
    envelope.event match {
      case event @ Event.Created(id, at) =>
        val movie = event(entityInfo.initialState)
        // just in case it was already there. In theory that can happen since the semantics is at least once.
        db.getMovie(movie.id.value).foreach(_ => db.deleteMovie(movie.id))
        db.createMovie(DBMovie(movie.id.value, movie.name, movie.createdAt, now()))
      case Event.NameChanged(newName) =>
        db.db.run(DBMovie.update(_.id === id).set(_.name := newName))
      case Event.Deleted(at) =>
        db.deleteMovie(Movie.Id(id))
    }
  }

  val projectionHandle: ProjectionRunner.ProjectionHandle =
    eventSourcing.registerProjection(entityInfo, movieProjection)

  def moviesMetadata: Vector[MovieMetadata] = db.movies.map { dbMovie =>
    MovieMetadata(dbMovie.typedId, dbMovie.name, dbMovie.lastUpdateAt)
  }

  def movieF(id: Movie.Id): Future[Option[Movie]] = movieEntity(id).ask(Movie.Command.Get.apply)

  def movie(id: Movie.Id): Option[Movie] = Await.result(movieF(id), Duration.Inf)

  def movieEvenNonExisting(id: Movie.Id): Movie = Await.result(
    movieEntity(id).ask(Movie.Command.RawGet.apply),
    Duration.Inf
  )

  def updateNameF(id: Movie.Id, newName: String): Future[Boolean] =
    movieEntity(id).ask(Movie.Command.ChangeName(newName, _))

  def updateName(id: Movie.Id, newName: String): Boolean = Await.result(updateNameF(id, newName), Duration.Inf)

  def createF(): Future[Movie.Id] = {
    val promise = Promise[Movie.Id]()

    case class MovieCreator(id: java.util.UUID) extends SimpleActor[Option[Movie]]() {
      override def run(msg: Option[Movie]): Unit = msg match {
        case None        => attempt()
        case Some(movie) => promise.success(movie.id)
      }
      def attempt(): Unit =
        eventSourcing.lastEntityId(entityInfo.entityKind).foreach { last =>
          val movieActor = eventSourcing.entity(last.getOrElse(-1) + 1, entityInfo)
          movieActor.send(Movie.Command.Create(this))
        }
    }

    MovieCreator(java.util.UUID.randomUUID()).attempt()

    promise.future
  }

  def create(): Movie.Id = Await.result(createF(), Duration.Inf)

  def deleteF(id: Movie.Id): Future[Boolean] = movieEntity(id).ask(Movie.Command.Delete.apply)

  def delete(id: Movie.Id): Boolean = Await.result(deleteF(id), Duration.Inf)

  private[movies] def pokeProjection()(using OnlyInTest): Unit =
    projectionHandle.poke()

}
