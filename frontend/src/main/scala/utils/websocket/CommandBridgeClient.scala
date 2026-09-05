package utils.websocket

import castorwire.{Bridge, CommandFrame, WireMessage}
import com.raquo.laminar.api.L.*
import eventsourcing.{EntityKind, EntityUpdateNotification}
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future, Promise}

/** Frontend side of `castorwire`: opens the one shared `/ws/commands` connection (see
  * `be.doeraene.routes.CommandRoutes` server-side).
  *
  * The main entry point is [[entity]]: it hands back a plain `castor.Actor[Command]` that ships every command sent to
  * it over this connection, so code that talks to it (e.g. via `be.doeraene.utils.castorutils.ask`, also usable here
  * since it's cross-compiled) can't tell it apart from a real in-process entity actor -- e.g.:
  * {{{
  *   given Codec[Movie.Command] = Movie.Command.codec(using commandBridge.bridge)
  *   val movie = commandBridge.entity(movieId.value, Movie.entityInfo.entityKind)
  *   movie.ask(Movie.Command.ChangeName(newName, _))
  * }}}
  * No wire-only DTOs are involved: `Movie.Command` (from `common`) is sent and decoded as itself on both sides,
  * `replyTo` included -- see `castorwire.Bridge.bridgedCodec` for how.
  *
  * One instance is meant to live for the whole page, like `HttpClient`/`MoviesService`.
  */
final class CommandBridgeClient(host: String = dom.document.location.host)(using
    ec: ExecutionContext,
    ctx: castor.Context
) {

  private val socket = {
    import urldsl.language.dummyErrorImpl.*
    JsonWebSocket[WireMessage, WireMessage](root / "commands", host)
  }

  /** Exposed so callers can build a bridge-aware codec for their own Command ADT, e.g.
    * `given Codec[Movie.Command] = Movie.Command.codec(using commandBridge.bridge)` -- see `MoviesService` (frontend)
    * for a full example.
    */
  val bridge: Bridge = Bridge(socket.outWriter.onNext)

  socket.inEvents.foreach {
    case WireMessage.Reply(token, payload) => bridge.deliver(token, payload)
    case _: WireMessage.Subscribe          => () // the server never subscribes to us (yet)
    case _: WireMessage.UnSubscribe        => () // the server never unsubscribes to us (yet)
    case WireMessage.Command(_)            => () // the server never pushes commands to us (yet)
  }(using unsafeWindowOwner)

  /** Connects the underlying websocket; safe to call once at app startup, mirroring `JsonWebSocket.open`. Outgoing
    * commands sent before the socket is open are buffered.
    */
  def open(): Future[Unit] = socket.open(using unsafeWindowOwner)

  def close(): Unit = socket.close()

  private def sendCommand[Command](entityKind: String, entityId: Int, command: Command)(using
      enc: Encoder[Command]
  ): Unit =
    socket.outWriter.onNext(WireMessage.Command(CommandFrame(entityKind, entityId, enc(command))))

  /** A `castor.Actor[Command]` that ships every command sent to it over this connection, to entity `id` of kind `kind`
    * (matches `EntityInformation.entityKind` server-side -- e.g. `Movie.entityInfo.entityKind`, so there's no separate
    * wire-only identifier to keep in sync). Fully opaque to callers: nothing distinguishes it from a real in-process
    * entity actor, so the usual `castor.Actor[T].ask` extension works on it unchanged.
    */
  def entity[Command](id: Int, kind: EntityKind[Command, ?])(using Encoder[Command]): castor.Actor[Command] =
    new castor.SimpleActor[Command]()(using ctx) {
      def run(command: Command): Unit = sendCommand(kind.name, id, command)
    }

  def subscribe[Entity](id: Int, kind: EntityKind[?, Entity])(using
      Decoder[Entity]
  ): (EventStream[Entity], () => Unit) = {
    val bus = new EventBus[Entity]

    var maxSequenceNumberSeen = -1

    val token = bridge.registerLocal(
      new castor.SimpleActor[EntityUpdateNotification[Entity]]() {
        override def run(msg: EntityUpdateNotification[Entity]): Unit =
          if msg.sequenceNumber > maxSequenceNumberSeen then {
            maxSequenceNumberSeen = msg.sequenceNumber
            bus.writer.onNext(msg.state)
          }
      },
      oneShot = false
    )

    val cancel = () => {
      bridge.unregister(token)
      socket.outWriter.onNext(WireMessage.UnSubscribe(token))
    }

    socket.outWriter.onNext(WireMessage.Subscribe(id, kind.name, token))
    (bus.events, cancel)
  }

  /** Lower-level than [[entity]]: sends one command and resolves a `Future` off a throwaway, one-shot replyTo actor,
    * without needing an entity handle first. Kept for one-off calls; prefer `entity(...).ask(...)` when you're calling
    * the same entity more than once.
    */
  def ask[Command, R](entityKind: String, entityId: Int)(mkCommand: castor.Actor[R] => Command)(using
      Encoder[Command],
      Decoder[R]
  ): Future[R] = {
    val promise = Promise[R]()
    val replyTo = new castor.SimpleActor[R]() {
      def run(r: R): Unit = promise.trySuccess(r)
    }
    sendCommand(entityKind, entityId, mkCommand(replyTo))
    promise.future
  }

}
