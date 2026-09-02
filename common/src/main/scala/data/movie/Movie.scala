package data.movie

import data.images.ImageData
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

}
