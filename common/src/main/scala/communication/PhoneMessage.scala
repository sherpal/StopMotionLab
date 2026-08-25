package communication

import communication.webrtc.{Answer, Offer, OnIceCandidateEvent, WebRTCCommProtocol}
import io.circe.Codec

sealed trait PhoneMessage

object PhoneMessage {

  sealed trait PhoneToServerMessage                                              extends PhoneMessage derives Codec
  case class WebRTCToServerWrapper(message: WebRTCCommProtocol.ProviderToServer) extends PhoneToServerMessage
  case object HeartBeat                                                          extends PhoneToServerMessage

  sealed trait ServerToPhoneMessage                                             extends PhoneMessage derives Codec
  case class WebRTCToPhoneWrapper(message: WebRTCCommProtocol.ServerToProvider) extends ServerToPhoneMessage
  case class ComputerAskedPicture(computerId: java.util.UUID)                   extends ServerToPhoneMessage

}
