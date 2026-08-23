package be.doeraene.services.connectedclients

import be.doeraene.websocket.TypedWsChannelActor
import communication.{ComputerMessage, PhoneMessage}

import java.util.concurrent.atomic.AtomicReference

class ConnectedClientsService {

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

}
