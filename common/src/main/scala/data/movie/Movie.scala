package data.movie

import data.images.ImageData
import io.circe.Codec

case class Movie(images: Vector[ImageData])

object Movie {
  given Codec[Movie] = io.circe.generic.semiauto.deriveCodec
}
