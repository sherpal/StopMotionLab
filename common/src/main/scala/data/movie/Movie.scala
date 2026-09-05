package data.movie

import castorwire.Bridge
import data.images.ImageData
import eventsourcing.{Effect, EntityInformation}
import io.circe.{Codec, Decoder, Encoder}
import urldsl.errors.DummyError
import urldsl.errors.DummyError.dummyError
import urldsl.vocabulary.{FromString, Printer}

case class Movie(
    id: Movie.Id,
    name: String,
    images: Vector[Movie.ImageDataWithOrdering],
    createdAt: Long,
    deletedAt: Long
) {
  def created: Boolean = createdAt > 0L
  def deleted: Boolean = deletedAt > 0L
  def active: Boolean  = created && !deleted
}

object Movie {
  given Codec[Movie] = io.circe.generic.semiauto.deriveCodec

  opaque type Id = Int

  object Id {
    given Codec[Id] = Codec.from(Decoder.decodeInt, Encoder.encodeInt)

    def apply(value: Int): Id = value

    def dummy: Id = -1

    extension (id: Id) {
      def value: Int = id
    }
  }

  given FromString[Id, DummyError] = _.toIntOption.toRight(dummyError)
  given Printer[Id]                = _.toString

  case class ImageDataWithOrdering(imageData: ImageData, maybeIndex: Option[Int]) derives Codec

  enum Command:
    case Create(replyTo: castor.Actor[Option[Movie]])
    case ChangeName(newName: String, replyTo: castor.Actor[Boolean])
    case Get(replyTo: castor.Actor[Option[Movie]])
    case Delete(replyTo: castor.Actor[Boolean])
    case RawGet(replyTo: castor.Actor[Movie])

    def handle(state: Movie, id: Int): Effect[Event, Movie] = {
      def now(): Long = System.currentTimeMillis() / 1000
      this match {
        case Command.Create(replyTo) =>
          if state.created then
            // already created, tell them and don't do anything
            Effect.Ignore().thenReply(replyTo)(_ => Option.empty)
          else Effect.Persist(Event.Created(Id(id), now())).thenReply(replyTo)(Some(_))
        case Command.ChangeName(newName, replyTo) =>
          if state.active then Effect.Persist(Event.NameChanged(newName)).thenReply(replyTo)(_ => true)
          else Effect.Ignore().thenReply(replyTo)(_ => false)
        case Command.Get(replyTo) =>
          Effect.ReplyTo(replyTo, movie => Option.when(movie.active)(movie))
        case Command.Delete(replyTo) =>
          if state.active then Effect.Persist(Event.Deleted(now())).thenReply(replyTo)(_ => true)
          else Effect.ReplyTo(replyTo, _ => false)
        case Command.RawGet(replyTo) =>
          Effect.ReplyTo(replyTo, identity)
      }
    }

  object Command {

    /** Not `derives Codec`: the `replyTo: castor.Actor[R]` fields need a live [[Bridge]] (one per connection) to become
      * wire-safe, and that bridge only exists once a client has actually connected -- long after this companion object
      * is compiled. So the codec is a plain method, explicitly parameterized by whichever connection's bridge is
      * relevant right now, instead of a `given` resolved once for everyone. See `castorwire.Bridge.bridgedCodec` for
      * how the `castor.Actor[R]` fields themselves are handled, with no change to this ADT beyond this method.
      */
    def codec(using Bridge): Codec[Command] = Bridge.bridgedCodec[Command]
  }

  enum Event derives Codec:
    case Created(id: Id, at: Long)
    case NameChanged(newName: String)
    case Deleted(at: Long)

    def apply(movie: Movie): Movie = this match {
      case Created(id, at) =>
        movie.copy(
          id = id,
          createdAt = at
        )
      case NameChanged(newName) =>
        movie.copy(name = newName)
      case Deleted(at) =>
        movie.copy(deletedAt = at)
    }

  val entityInfo: EntityInformation[Command, Event, Movie] =
    EntityInformation.usingCirceSerialization[Movie.Command, Movie.Event, Movie](
      Movie(Movie.Id.dummy, "Untitled", Vector.empty, createdAt = 0L, deletedAt = 0L),
      _(_),
      (command, state, id) => command.handle(state, id)
    )

}
