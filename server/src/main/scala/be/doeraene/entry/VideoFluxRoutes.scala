package be.doeraene.entry

import be.doeraene.websocket.TypedWsChannelActor
import communication.webrtc.WebRTCCommProtocol
import communication.{ComputerMessage, PhoneMessage}

import java.util.concurrent.atomic.AtomicReference

class VideoFluxRoutes(using castor.Context, cask.util.Logger) extends cask.Routes {

  private val connectedPhones: AtomicReference[Map[
    java.util.UUID,
    TypedWsChannelActor[PhoneMessage.PhoneToServerMessage, PhoneMessage.ServerToPhoneMessage]
  ]] = AtomicReference(Map.empty)
  private val connectedComputers: AtomicReference[Map[
    java.util.UUID,
    (
        channel: TypedWsChannelActor[ComputerMessage.ComputerToServerMessage, ComputerMessage.ServerToComputerMessage],
        waitingForPhone: Boolean
    )
  ]] = AtomicReference(Map.empty)

  @cask.websocket("/ws/phone")
  def phone() = cask.WsHandler { underlying =>
    val channel = TypedWsChannelActor[PhoneMessage.PhoneToServerMessage, PhoneMessage.ServerToPhoneMessage](underlying)
    val id      = java.util.UUID.randomUUID()

    connectedPhones.updateAndGet(_ + (id -> channel))

    connectedComputers.updateAndGet {
      _.map { case (computerId, (computerChannel, isWaitingForPhone)) =>
        if isWaitingForPhone then {
          channel.send(PhoneMessage.WebRTCToPhoneWrapper(WebRTCCommProtocol.ForwardAskOffer(computerId)))
        }
        computerId -> (channel = computerChannel, waitingForPhone = false)
      }
    }

    channel.actor(
      {
        case PhoneMessage.WebRTCToServerWrapper(message) =>
          println(message)
          val computerId = message.recipientId
          connectedComputers
            .get()
            .get(computerId)
            .foreach(_.channel.send(ComputerMessage.WebRTCToComputerWrapper(message.forward(id))))
          None
        case PhoneMessage.HeartBeat                       => None
        case PhoneMessage.PictureData(recipient, dataUrl) =>
          connectedComputers.get().get(recipient).foreach(_.channel.send(ComputerMessage.PictureData(dataUrl)))

          None
      },
      { case cask.Ws.Close(code, reason) =>
        println(s"Websocket from phone closed ($code, $reason)")
        connectedPhones.updateAndGet(_ - id)
        connectedComputers
          .get()
          .values
          .foreach(_.channel.send(ComputerMessage.WebRTCToComputerWrapper(WebRTCCommProtocol.OfferClosed(id))))
        ()
      }
    )
  }

  @cask.websocket("/ws/computer")
  def computer() = cask.WsHandler { underlying =>
    val channel =
      TypedWsChannelActor[ComputerMessage.ComputerToServerMessage, ComputerMessage.ServerToComputerMessage](underlying)

    val id = java.util.UUID.randomUUID()

    connectedComputers.updateAndGet(_ + (id -> (channel = channel, waitingForPhone = false)))

    channel.actor(
      {
        case ComputerMessage.WebRTCToServerWrapper(message) =>
          val providerId = message.recipientId
          connectedPhones.get().get(providerId).foreach(_.send(PhoneMessage.WebRTCToPhoneWrapper(message.forward(id))))
          None
        case ComputerMessage.AskOffer() =>
          println(s"Received ask offer request from $id")
          connectedPhones.get().headOption match {
            case None =>
              println("No phone currently available, waiting...")
              connectedComputers.updateAndGet(_ + (id -> (channel = channel, waitingForPhone = true)))
            case Some(phone) =>
              println("Found a phone available")
              phone._2.send(PhoneMessage.WebRTCToPhoneWrapper(WebRTCCommProtocol.ForwardAskOffer(id)))
          }
          None
        case ComputerMessage.AskPicture(phoneId) =>
          connectedPhones.get().get(phoneId) match {
            case None =>
              println(s"No phone with id $phoneId")
            case Some(phone) =>
              phone.send(PhoneMessage.ComputerAskedPicture(id))
          }
          None
      },
      { case cask.Ws.Close(_, _) =>
        println("Websocket closed for computer")
        connectedComputers.updateAndGet(_ - id)
        ()
      }
    )
  }

  initialize()

}
