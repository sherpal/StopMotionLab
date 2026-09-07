package utils.websocket

import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.Owner
import io.circe.parser.decode
import io.circe.{Decoder, Encoder}
import org.scalajs.dom
import org.scalajs.dom.{Event, WebSocket}
import urldsl.language.QueryParameters.dummyErrorImpl.*
import urldsl.language.*
import com.raquo.laminar.nodes.ReactiveElement
import com.raquo.laminar.nodes.ReactiveElement.Base

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}

/** Prepares a WebSocket to connect to the specified url. The connection actually occurs when you run the `open` method.
  *
  * Messages coming from the server can be retrieved using the `$in` [[com.raquo.airstream.eventstream.EventStream]] and
  * sending messages to the server can be done by writing to the `outWriter` [[com.raquo.airstream.core.Observer]]
  */
final class JsonWebSocket[In, Out, P, Q] private (
    pathWithQueryParams: PathSegmentWithQueryParams[P, ?, Q, ?],
    p: P,
    q: Q,
    host: String,
    bufferEarlyMessage: Boolean = true
)(using
    decoder: Decoder[In],
    encoder: Encoder[Out]
)(using ExecutionContext) {

  private def url: String =
    "wss://" + host + "/ws/" + pathWithQueryParams.createUrlString(p, q)

  private lazy val socket = new WebSocket(url)

  private val inBus: EventBus[In]           = new EventBus
  private val outBus: EventBus[Out]         = new EventBus
  private val closeBus: EventBus[Unit]      = new EventBus
  private val errorBus: EventBus[dom.Event] = new EventBus
  private val openBus: EventBus[dom.Event]  = new EventBus

  private var isOpen: Boolean = false

  private def openWebSocketConnection(using owner: Owner) = Future {
    val webSocket = socket
    webSocket.onmessage = (event: dom.MessageEvent) =>
      decode[In](event.data.asInstanceOf[String]) match {
        case Right(in)   => inBus.writer.onNext(in)
        case Left(error) =>
          dom.console.log("data", event.data)
          dom.console.error(error)
      }
    outBus.events.map(encoder(_).noSpaces).foreach(webSocket.send)
    webSocket.onopen = (event: Event) => {
      openBus.writer.onNext(event)
      isOpen = true
      flushBuffer()
    }
    webSocket.onerror = (event: Event) => {
      if (scala.scalajs.LinkingInfo.developmentMode) {
        dom.console.error(event)
      }
      errorBus.writer.onNext(event)
    }
    webSocket.onclose = (_: Event) => closeBus.writer.onNext(())
  }

  def open(using Owner): Future[Unit] = openWebSocketConnection

  def modifier[El <: ReactiveElement.Base]: Modifier[Base] =
    onMountUnmountCallback(
      ctx => open(using ctx.owner),
      _ => socket.close()
    )

  def close(): Unit = {
    socket.close()
    closeBus.writer.onNext(())
  }

  val inEvents: EventStream[In] = inBus.events
  val outWriter: Observer[Out]  = Observer.combine(
    outBus.writer.filter(_ => isOpen),
    Observer[Out](out => if !isOpen then bufferedOutMessages = bufferedOutMessages.enqueue(out))
  )
  val closedSignal: Signal[Boolean]   = closeBus.events.mapTo(true).startWith(false)
  val errorEvents: EventStream[Event] = errorBus.events
  val openEvents: EventStream[Event]  = openBus.events
  val isOpenSignal: Signal[Boolean] =
    openEvents.mapTo(true).startWith(false).withCurrentValueOf(closedSignal).map(_ && !_)

  private var bufferedOutMessages = Queue.empty[Out]

  @tailrec private def flushBuffer(): Unit = if bufferedOutMessages.nonEmpty then {
    val (next, nextQueue) = bufferedOutMessages.dequeue
    outBus.writer.onNext(next)
    bufferedOutMessages = nextQueue
    flushBuffer()
  }

}

object JsonWebSocket {

  def apply[In, Out](path: PathSegment[Unit, ?], host: String = dom.document.location.host)(using
      Decoder[In],
      Encoder[Out]
  )(using ExecutionContext): JsonWebSocket[In, Out, Unit, Unit] = new JsonWebSocket(path ? ignore, (), (), host)

  /** Same idea as `apply`, but for a path segment carrying a value (e.g. a `:editorId`-style segment), so the
    * resulting URL matches a cask route that takes a path parameter. Named differently from `apply` because an
    * overload sharing its arity/erasure with a default-having `apply` overload confuses Scala's overload resolution.
    */
  def withPathValue[In, Out, P](path: PathSegment[P, ?], p: P, host: String = dom.document.location.host)(using
      Decoder[In],
      Encoder[Out]
  )(using ExecutionContext): JsonWebSocket[In, Out, P, Unit] = new JsonWebSocket(path ? ignore, p, (), host)

  def apply[In, Out, Q](
      path: PathSegment[Unit, ?],
      query: QueryParameters[Q, ?],
      q: Q,
      host: String
  )(using Decoder[In], Encoder[Out])(using ExecutionContext): JsonWebSocket[In, Out, Unit, Q] =
    new JsonWebSocket(path ? query, (), q, host)

  def apply[In, Out, Q](
      path: PathSegment[Unit, ?],
      query: QueryParameters[Q, ?],
      q: Q
  )(using Decoder[In], Encoder[Out])(using ExecutionContext): JsonWebSocket[In, Out, Unit, Q] =
    apply(path, query, q, dom.document.location.host)

}
