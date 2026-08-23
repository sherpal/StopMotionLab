package data.images

import io.circe.{Codec, Decoder, Encoder}
import urldsl.errors.DummyError
import urldsl.vocabulary.{FromString, Printer}

import java.util.UUID
import scala.util.Try

case class ImageData(id: ImageData.Id) derives Codec

object ImageData {

  enum MimeType:
    case Jpeg, Png

    def value: String = this match {
      case MimeType.Jpeg => "image/jpeg"
      case MimeType.Png  => "image/png"
    }

  object MimeType {
    def fromString(value: String): Either[String, MimeType] =
      MimeType.values.find(mime => value.toLowerCase().contains(mime.value)).toRight(s"Unknow image mime type: $value")

    def unsafeFromString(value: String): MimeType =
      fromString(value).left.map(err => IllegalArgumentException(err)).toTry.get
  }

  opaque type Id = UUID

  object Id {
    given Codec[Id] = Codec.from(Decoder.decodeUUID, Encoder.encodeUUID)

    def random(): Id = UUID.randomUUID()

    inline def fromUUID(id: UUID): Id = id

    extension (id: Id) {
      inline def uuidValue: UUID = id
    }
  }

  given FromString[Id, DummyError] =
    FromString.factory(str => Try(java.util.UUID.fromString(str)).toEither.left.map(_ => DummyError.dummyError))
  given Printer[Id] = Printer.factory(_.toString)

}
