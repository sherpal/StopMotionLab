package be.doeraene.services.movies

import be.doeraene.services.database.DatabaseService
import castor.SimpleActor
import data.movie.{Movie, MovieMetadata}
import eventsourcing.{Effect, EntityInformation, EventSourcingService, Projection, ProjectionRunner}
import be.doeraene.utils.castorutils.ask
import data.movie.Movie.Event
import be.doeraene.services.database.tables.Movie as DBMovie
import be.doeraene.utils.testshenanigans.OnlyInTest
import scalasql.simple.SqliteDialect

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}

class MoviesService()(using db: DatabaseService, eventSourcing: EventSourcingService)(using castor.Context) {

  private def now(): Long = System.currentTimeMillis() / 1000

  private val entityInfo = EntityInformation.usingCirceSerialization[Movie.Command, Movie.Event, Movie](
    Movie(Movie.Id.dummy, "Untitled", Vector.empty, createdAt = 0L, deletedAt = 0L),
    _(_),
    (command, state, id) =>
      command match {
        case Movie.Command.Create(replyTo) =>
          if state.created then
            // already created, tell them and don't do anything
            Effect.Ignore().thenReply(replyTo)(_ => Option.empty)
          else Effect.Persist(Movie.Event.Created(Movie.Id(id), now())).thenReply(replyTo)(Some(_))
        case Movie.Command.ChangeName(newName, replyTo) =>
          if state.active then Effect.Persist(Movie.Event.NameChanged(newName)).thenReply(replyTo)(_ => true)
          else Effect.Ignore().thenReply(replyTo)(_ => false)
        case Movie.Command.Get(replyTo) =>
          Effect.ReplyTo(replyTo, movie => Option.when(movie.active)(movie))
        case Movie.Command.Delete(replyTo) =>
          if state.active then Effect.Persist(Movie.Event.Deleted(now())).thenReply(replyTo)(_ => true)
          else Effect.ReplyTo(replyTo, _ => false)
        case Movie.Command.RawGet(replyTo) =>
          Effect.ReplyTo(replyTo, identity)
      }
  )

  private def movieEntity(id: Movie.Id) = eventSourcing.entity(id.value, entityInfo)

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

  val projectionHandle: ProjectionRunner.ProjectionHandle = eventSourcing.registerProjection(entityInfo, movieProjection)

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
      def attempt(): Unit = {
        val idAttempt  = eventSourcing.lastEntityId(entityInfo.entityKind).getOrElse(-1) + 1
        val movieActor = eventSourcing.entity(idAttempt, entityInfo)
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
