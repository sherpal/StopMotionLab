package data.movie

import io.circe.Codec

case class MovieMetadata(id: Movie.Id, name: String, lastUpdateAt: Long) derives Codec
