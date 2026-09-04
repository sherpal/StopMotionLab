package utils.websocket

import castorwire.{Bridge, CommandFrame, WireMessage}
import com.raquo.laminar.api.L.unsafeWindowOwner
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future, Promise}

/** Frontend side of `castorwire`: opens the one shared `/ws/commands` connection (see
  * `be.doeraene.routes.CommandRoutes` server-side) and exposes an `ask`-style call that
  * mirrors `be.doeraene.utils.castorutils.ask`, used server-side, so a command reads the
  * same way on both ends -- e.g.:
  * {{{
  *   given Codec[Movie.Command] = Movie.Command.codec(using commandBridge.bridge)
  *   commandBridge.ask("data.movie.Movie", movieId.value)(Movie.Command.ChangeName(newName, _))
  * }}}
  * No wire-only DTOs are involved: `Movie.Command` (from `common`) is sent and decoded as
  * itself on both sides, `replyTo` included -- see `castorwire.Bridge.bridgedCodec` for how.
  *
  * One instance is meant to live for the whole page, like `HttpClient`/`MoviesService`; it
  * uses Laminar's `unsafeWindowOwner` for that reason, not a component's own owner.
  */
final class CommandBridgeClient(host: String = dom.document.location.host)(using ExecutionContext) {

  given ctx: castor.Context = castor.Context.Simple.global

  private val socket = {
    import urldsl.language.dummyErrorImpl.*
    JsonWebSocket[WireMessage, WireMessage](root / "commands", host)
  }

  /** Exposed so callers can build a bridge-aware codec for their own Command ADT, e.g.
    * `given Codec[Movie.Command] = Movie.Command.codec(using commandBridge.bridge)` --
    * see `MoviesService.updateNameWs` (frontend) for a full example.
    */
  val bridge: Bridge = Bridge(socket.outWriter.onNext)

  socket.inEvents.foreach {
    case WireMessage.Reply(token, payload) => bridge.deliver(token, payload)
    case WireMessage.Command(_)            => () // the server never pushes commands to us (yet)
  }(using unsafeWindowOwner)

  /** Connects the underlying websocket; safe to call once at app startup, mirroring
    * `JsonWebSocket.open`. Outgoing commands sent before the socket is open are buffered.
    */
  def open(): Future[Unit] = socket.open(using unsafeWindowOwner)

  def close(): Unit = socket.close()

  /** Sends the command built by `mkCommand` to entity `entityId` of kind `entityKind`
    * (matches `EntityInformation.entityKind.name` server-side, e.g. `"data.movie.Movie"`),
    * and completes once that entity actually replies.
    */
  def ask[Command, R](entityKind: String, entityId: Int)(mkCommand: castor.Actor[R] => Command)(using
      Encoder[Command],
      Decoder[R]
  ): Future[R] = {
    val promise = Promise[R]()
    val replyTo = new castor.SimpleActor[R]() {
      def run(r: R): Unit = promise.trySuccess(r)
    }
    val commandJson = mkCommand(replyTo).asJson
    socket.outWriter.onNext(WireMessage.Command(CommandFrame(entityKind, entityId, commandJson)))
    promise.future
  }

}
