package communication

import communication.webrtc.WebRTCCommProtocol
import data.images.ImageData
import io.circe.Codec

sealed trait ComputerMessage

object ComputerMessage {

  sealed trait ComputerToServerMessage extends ComputerMessage derives Codec
  case class WebRTCToServerWrapper(rtcMessage: WebRTCCommProtocol.ConsumerToServer) extends ComputerToServerMessage
  case class AskPicture(phoneId: String, requestId: Long)                           extends ComputerToServerMessage

  sealed trait ServerToComputerMessage extends ComputerMessage derives Codec
  case class WebRTCToComputerWrapper(rtcMessage: WebRTCCommProtocol.ServerToConsumer) extends ServerToComputerMessage
  case class ThisIsYourId(id: String)                                                 extends ServerToComputerMessage
  case class PhoneTookPicture(requestId: Long)                                        extends ServerToComputerMessage

}
