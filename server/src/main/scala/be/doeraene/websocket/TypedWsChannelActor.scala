package be.doeraene.websocket

import io.circe.{Decoder, Encoder}
import io.circe.syntax.*
import io.circe.parser.decode

class TypedWsChannelActor[In, Out](underlying: cask.WsChannelActor)(using
    encoder: Encoder[Out],
    decoder: Decoder[In]
) {

  def send(out: Out): Unit = underlying.send(cask.Ws.Text(out.asJson.noSpaces))

  def actor(handleIn: In => Option[Out], raw: PartialFunction[cask.Ws.Event, Unit])(using
      castor.Context,
      cask.util.Logger
  ): cask.WsActor = cask.WsActor {
    case text: cask.Ws.Text =>
      decode[In](text.value) match {
        case Right(in) => handleIn(in).foreach(send)
        case Left(err) =>
          println(s"Received un-recognized text message, fallback to raw")
          raw.applyOrElse(text, _ => ())
      }
    case other: cask.Ws.Event =>
      raw.applyOrElse(other, _ => ())
  }

}
