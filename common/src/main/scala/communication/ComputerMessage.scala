package communication

import communication.webrtc.WebRTCCommProtocol
import io.circe.Codec

sealed trait ComputerMessage

object ComputerMessage {

  sealed trait ComputerToServerMessage extends ComputerMessage derives Codec
  case class WebRTCToServerWrapper(rtcMessage: WebRTCCommProtocol.ConsumerToServer) extends ComputerToServerMessage
  case class AskOffer()                                                             extends ComputerToServerMessage
  case class AskPicture(phoneId: java.util.UUID)                                    extends ComputerToServerMessage

  sealed trait ServerToComputerMessage extends ComputerMessage derives Codec
  case class WebRTCToComputerWrapper(rtcMessage: WebRTCCommProtocol.ServerToConsumer) extends ServerToComputerMessage
  case class PictureData(dataUrl: String)                                             extends ServerToComputerMessage

}
