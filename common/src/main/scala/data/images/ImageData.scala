package data.images

import io.circe.Codec

case class ImageData(dataUrl: String) derives Codec
