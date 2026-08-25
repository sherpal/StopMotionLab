package be.doeraene.routes

import io.circe.Encoder

trait Helpers {

  def json[T](t: T)(using encoder: Encoder[T]): cask.Response[String] = cask.Response(
    encoder(t).noSpaces,
    headers = Seq(
      "Content-Type" -> "application/json"
    )
  )

}
