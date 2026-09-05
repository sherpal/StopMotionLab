package castorwire

import io.circe.{Codec, Decoder, Encoder}

import scala.collection.concurrent.TrieMap
import scala.deriving.Mirror
import java.util.concurrent.atomic.AtomicLong

/** One `Bridge` per open connection (websocket, ...). Lets a `castor.Actor[R]` embedded inside a message cross the wire
  * without the actor itself ever being serialized: encoding it registers it under a fresh opaque token, decoding a
  * token builds a stand-in actor that ships whatever it's sent back down the same connection, tagged with that token.
  *
  * This makes any Command ADT of the shape `case SomeCommand(..., replyTo: castor.Actor[R])` codec-derivable via
  * [[Bridge.bridgedCodec]] with zero change to the ADT itself -- see [[data.movie.Movie.Command]]. The bridge to use is
  * always an explicit parameter, never ambient state: a given `Codec[Command]` would have to be resolved once, at
  * compile time, before any real connection (and its Bridge) exists, so it's a plain method instead, called with
  * whichever connection's Bridge is relevant at that moment.
  */
final class Bridge(sendMessage: WireMessage => Unit)(using ctx: castor.Context) {

  private val registry = TrieMap.empty[String, io.circe.Json => Unit]
  private val counter  = AtomicLong(0)

  /** Registers `actor` and returns the opaque token that stands in for it on the wire. `oneShot` (the default) removes
    * the registration after the first delivered message, matching the reply-exactly-once `ask`-style commands used
    * throughout this codebase. Pass `oneShot = false` for an actor meant to receive more than one message over time
    * (e.g. a future subscription-style command).
    */
  def registerLocal[R](actor: castor.Actor[R], oneShot: Boolean = true)(using d: Decoder[R]): String = {
    val token = s"a${counter.incrementAndGet()}"
    registry(token) = json => {
      if oneShot then registry.remove(token)
      d.decodeJson(json) match {
        case Right(r)  => actor.send(r)
        case Left(err) => System.err.println(s"castorwire: failed to decode message for $token: $err")
      }
    }
    token
  }

  /** Builds a proxy actor for a token that was handed to us by the other side: sending to it serializes the message and
    * ships it back down this connection.
    */
  def remoteProxy[R](token: String)(using e: Encoder[R]): castor.Actor[R] =
    new castor.SimpleActor[R]()(using ctx) {
      def run(r: R): Unit = sendMessage(WireMessage.Reply(token, e(r)))
    }

  /** Call this when a [[WireMessage.Reply]] frame arrives on this connection: routes the payload to whichever local
    * actor was registered under that token, if any (it may legitimately be gone already for a one-shot registration
    * that already fired, or if this frame is stale/bogus).
    */
  def deliver(token: String, payload: io.circe.Json): Unit = registry.get(token).foreach(_(payload))

  def unregister(token: String): Unit = registry.remove(token)

  /** Call on disconnect to drop any still-registered (e.g. non-one-shot) actors. */
  def clear(): Unit = registry.clear()
}

object Bridge {

  /** Derives a `Codec[A]` for any ADT `A` (typically a Command enum) that may embed `castor.Actor[R]` fields, resolving
    * `bridge` (an ordinary parameter, not ambient state) into those fields' wire representation. `inline` matters here:
    * it splices this whole body -- including the two local givens below -- at each call site, which is what lets the
    * nested `deriveCodec[A]` macro call see them.
    *
    * Usage, once per entity's Command ADT (see [[data.movie.Movie.Command]]):
    * {{{
    *   object Command {
    *     def codec(using Bridge): Codec[Command] = Bridge.bridgedCodec[Command]
    *   }
    * }}}
    */
  inline def bridgedCodec[A](using bridge: Bridge, m: Mirror.Of[A]): Codec[A] = {
    given [R](using d: Decoder[R]): Encoder[castor.Actor[R]] =
      Encoder.encodeString.contramap(bridge.registerLocal(_))

    given [R](using e: Encoder[R]): Decoder[castor.Actor[R]] =
      Decoder.decodeString.map(bridge.remoteProxy[R](_))

    io.circe.generic.semiauto.deriveCodec[A]
  }
}
