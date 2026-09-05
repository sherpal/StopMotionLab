package be.doeraene.routes

import be.doeraene.websocket.TypedWsChannelActor
import castorwire.{Bridge, CommandRouter, WireMessage}

/** Generic entry point for sending entity commands from the frontend over a websocket -- see `castorwire.Bridge` for
  * how a Command ADT's `replyTo: castor.Actor[R]` fields survive the trip without ever being duplicated into a separate
  * wire ADT.
  *
  * `routers` is the whitelist of entity kinds actually reachable this way; each service that wants to expose (part of)
  * its Command ADT registers one (see `MoviesService.commandRouter`).
  */
//noinspection TypeAnnotation
class CommandRoutes(routers: Seq[CommandRouter])(using castor.Context, cask.util.Logger) extends cask.Routes {

  private val routersByKind: Map[String, CommandRouter] = routers.map(r => r.entityKind -> r).toMap

  @cask.websocket("/ws/commands")
  def commands() = cask.WsHandler { underlying =>
    val channel: TypedWsChannelActor[WireMessage, WireMessage] = TypedWsChannelActor(underlying)

    val bridge: Bridge = Bridge(channel.send)

    var subscriptions: Map[String, () => Unit] = Map.empty

    channel.actor(
      {
        case WireMessage.Command(frame) =>
          routersByKind.get(frame.entityKind) match {
            case Some(router) => router.dispatch(frame.entityId, frame.command, bridge)
            case None         => System.err.println(s"No command router registered for entity kind ${frame.entityKind}")
          }
          None
        case WireMessage.Subscribe(id, entityKind, token) =>
          routersByKind.get(entityKind) match {
            case Some(router) =>
              subscriptions = subscriptions + (token -> router.subscribe(id, token, bridge))
            case None => System.err.println(s"No command router registered for entity kind ${entityKind}")
          }
          None
        case WireMessage.UnSubscribe(token) =>
          subscriptions.get(token).foreach(_())
          subscriptions -= token
          None
        case WireMessage.Reply(token, payload) =>
          // a reply directed at an actor *we* registered -- relevant once this connection
          // is also used to push commands the other way (e.g. subscriptions); unused today.
          bridge.deliver(token, payload)
          None
      },
      { case cask.Ws.Close(_, _) =>
        subscriptions.values.foreach(_())
        subscriptions = Map.empty
        bridge.clear()
      }
    )
  }

  initialize()

}
