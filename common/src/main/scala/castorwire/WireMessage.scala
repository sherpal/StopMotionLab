package castorwire

import io.circe.{Codec, Json}

/** A command addressed to one entity, generically: `entityKind` names the aggregate type (matches
  * `EntityKind.name`/`EntityInformation.entityKind.name` on the server), `entityId` the instance, and `command` is that
  * entity's own Command ADT, encoded with a [[Bridge]] active (see `Bridge.withBridge`) so that any embedded
  * `replyTo: castor.Actor[R]` field becomes an opaque token instead of failing to serialize.
  */
case class CommandFrame(entityKind: String, entityId: Int, command: Json) derives Codec

/** Everything that can travel over one castorwire-bridged connection. */
enum WireMessage derives Codec:
  /** A command from the initiating side, to be routed by entity kind -- see [[CommandRouter]]. */
  case Command(frame: CommandFrame)

  /** A message sent to a previously-registered actor, identified by its token -- see [[Bridge.registerLocal]] /
    * [[Bridge.remoteProxy]]. Typically a reply to a command, but nothing here assumes request/response: a non-one-shot
    * registration can receive many of these over time.
    */
  case Reply(token: String, payload: Json)

  case Subscribe(id: Int, entityKind: String, token: String)
  
  case UnSubscribe(token: String)

/** Knows how to decode and dispatch commands for one entity kind. Register one per entity kind that should be reachable
  * over a castorwire connection -- this is the whitelist of what's actually exposed to the wire, and the only place
  * that needs to know both the wire format (`Json`) and the real Command ADT.
  */
trait CommandRouter {
  def entityKind: String
  def dispatch(entityId: Int, command: Json, bridge: Bridge): Unit
  def subscribe(entityId: Int, actorToken: String, bridge: Bridge): () => Unit
}
