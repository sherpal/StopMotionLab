package communication.webrtc

import io.circe.Codec

sealed trait WebRTCCommProtocol

object WebRTCCommProtocol {

  sealed trait ProviderToServer extends WebRTCCommProtocol derives Codec:
    def recipientId: String
    def forward(id: String): ServerToConsumer

  sealed trait ConsumerToServer extends WebRTCCommProtocol derives Codec:
    def recipientId: String
    def forward(id: String): ServerToProvider
  case class SendOffer(offer: Offer, recipientId: String) extends ProviderToServer:
    def forward(id: String): ForwardOffer = ForwardOffer(offer, id)
  case class SendOnIceCandidateEvent(event: OnIceCandidateEvent, recipientId: String)
      extends ProviderToServer
      with ConsumerToServer:
    def forward(id: String): ForwardOnIceCandidateEvent =
      ForwardOnIceCandidateEvent(event, id)
  case class SendAnswer(answer: Answer, recipientId: String) extends ConsumerToServer:
    def forward(id: String): ForwardAnswer = ForwardAnswer(answer, id)
  case class WillSendOffer(recipientId: String) extends ProviderToServer:
    def forward(id: String): ForwardWillSendOffer = ForwardWillSendOffer(id)

  sealed trait ServerToClient extends WebRTCCommProtocol:
    def id: String
  sealed trait ServerToProvider                     extends ServerToClient derives Codec
  sealed trait ServerToConsumer                     extends ServerToClient derives Codec
  case class ForwardOffer(offer: Offer, id: String) extends ServerToConsumer
  case class ForwardOnIceCandidateEvent(event: OnIceCandidateEvent, id: String)
      extends ServerToConsumer
      with ServerToProvider
  case class OfferClosed(id: String)                   extends ServerToConsumer
  case class ForwardAnswer(answer: Answer, id: String) extends ServerToProvider
  case class ForwardAskOffer(id: String)               extends ServerToProvider
  case class ForwardWillSendOffer(id: String)          extends ServerToConsumer

}
