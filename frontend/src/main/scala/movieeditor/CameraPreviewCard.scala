package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.IconName
import be.doeraene.webcomponents.ui5.{Card, Icon, Text}
import com.raquo.laminar.api.L.*
import communication.ComputerMessage
import communication.webrtc.WebRTCCommProtocol
import utils.webrtc.WebRTCConnection

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** The "Aperçu caméra" card: renders the live WebRTC video feed once a phone camera is connected, or a
  * placeholder while waiting for one.
  */
object CameraPreviewCard {

  def apply(
      currentProviderIdSignal: Signal[Option[String]],
      socketWriter: Observer[ComputerMessage.ComputerToServerMessage],
      socketMessages: EventStream[WebRTCCommProtocol.ServerToConsumer]
  )(using ExecutionContext): HtmlElement =
    Card.of(
      _ => display.block,
      _ => width.percent := 100,
      _ => boxSizing.borderBox,
      _.slots.header := Card.header.of(_.titleText := "Aperçu caméra"),
      _ =>
        div(
          padding.px   := 8,
          minHeight.px := 216,
          display.flex,
          alignItems.center,
          justifyContent.center,
          child <-- currentProviderIdSignal.map {
            case Some(id) => videoFeed(id, socketWriter, socketMessages)
            case None =>
              div(
                display.flex,
                flexDirection.column,
                alignItems.center,
                gap.px  := 8,
                opacity := 0.5,
                Icon.of(_.name := IconName.camera),
                Text("En attente d'une caméra…")
              )
          }
        )
    )

  private def videoFeed(
      providerId: String,
      socketWriter: Observer[ComputerMessage.ComputerToServerMessage],
      socketMessages: EventStream[WebRTCCommProtocol.ServerToConsumer]
  )(using ExecutionContext): HtmlElement = {
    println(s"Provider id $providerId")
    val webRTCConnection =
      WebRTCConnection.Consumer(socketWriter.contramap(ComputerMessage.WebRTCToServerWrapper(_)), providerId)

    div(
      className := "smlab-framed",
      child.maybe <-- webRTCConnection.videoStreamSignal.map(_.map { stream =>
        videoTag(
          maxWidth.percent := 100,
          display.block,
          onMountCallback { ctx =>
            ctx.thisNode.ref.srcObject = stream
            ctx.thisNode.ref.play()
          }
        )
      }),
      onMountCallback { el =>
        webRTCConnection.init().onComplete {
          case Failure(exception) => throw exception
          case Success(())        => println(s"Web rtc to phone $providerId intialized")
        }
      },
      socketMessages --> webRTCConnection.inMessageObserver
    )
  }

}
