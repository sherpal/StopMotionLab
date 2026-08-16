package communication.webrtc

import io.circe.Codec

sealed trait WebRTCCommProtocol

object WebRTCCommProtocol {

  sealed trait ProviderToServer extends WebRTCCommProtocol derives Codec:
    def recipientId: java.util.UUID
    def forward(id: java.util.UUID): ServerToConsumer

  sealed trait ConsumerToServer extends WebRTCCommProtocol derives Codec:
    def recipientId: java.util.UUID
    def forward(id: java.util.UUID): ServerToProvider
  case class SendOffer(offer: Offer, recipientId: java.util.UUID) extends ProviderToServer:
    def forward(id: java.util.UUID): ForwardOffer = ForwardOffer(offer, id)
  case class SendOnIceCandidateEvent(event: OnIceCandidateEvent, recipientId: java.util.UUID)
      extends ProviderToServer
      with ConsumerToServer:
    def forward(id: java.util.UUID): ForwardOnIceCandidateEvent =
      ForwardOnIceCandidateEvent(event, id)
  case class SendAnswer(answer: Answer, recipientId: java.util.UUID) extends ConsumerToServer:
    def forward(id: java.util.UUID): ForwardAnswer = ForwardAnswer(answer, id)
  case class WillSendOffer(recipientId: java.util.UUID) extends ProviderToServer:
    def forward(id: java.util.UUID): ForwardWillSendOffer = ForwardWillSendOffer(id)

  sealed trait ServerToClient extends WebRTCCommProtocol:
    def id: java.util.UUID
  sealed trait ServerToProvider                             extends ServerToClient derives Codec
  sealed trait ServerToConsumer                             extends ServerToClient derives Codec
  case class ForwardOffer(offer: Offer, id: java.util.UUID) extends ServerToConsumer
  case class ForwardOnIceCandidateEvent(event: OnIceCandidateEvent, id: java.util.UUID)
      extends ServerToConsumer
      with ServerToProvider
  case class OfferClosed(id: java.util.UUID)                   extends ServerToConsumer
  case class ForwardAnswer(answer: Answer, id: java.util.UUID) extends ServerToProvider
  case class ForwardAskOffer(id: java.util.UUID)               extends ServerToProvider
  case class ForwardWillSendOffer(id: java.util.UUID)          extends ServerToConsumer

}
