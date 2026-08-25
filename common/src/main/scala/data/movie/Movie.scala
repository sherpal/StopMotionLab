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
    lastUpdatedAt: Long,
    deleted: Boolean
)

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

}
