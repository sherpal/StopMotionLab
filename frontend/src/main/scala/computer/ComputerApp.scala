package computer

import be.doeraene.webcomponents.ui5.Button
import com.raquo.laminar.api.L.*
import communication.webrtc.WebRTCCommProtocol
import communication.ComputerMessage
import data.images.ImageData
import data.movie.Movie
import org.scalajs.dom
import org.scalajs.dom.BlobPropertyBag
import services.LocalStorageService
import utils.websocket.JsonWebSocket
import urldsl.language.dummyErrorImpl.*
import utils.webrtc.WebRTCConnection

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.JSRichIterableOnce
import scala.util.{Failure, Success}

object ComputerApp {

  def apply()(using ExecutionContext): HtmlElement = {
    val storage = LocalStorageService()

    val websocket = JsonWebSocket[ComputerMessage.ServerToComputerMessage, ComputerMessage.ComputerToServerMessage](
      root / "computer"
    )

    val currentProviderIdVar = Var(Option.empty[java.util.UUID])

    val picturesVar   = Var(Vector.empty[String])
    val askPictureBus = new EventBus[Unit]

    def movieDisplay = {
//      div(
//        MovieDisplay(
//          picturesVar.signal.map(_.map(ImageData(_))),
//          picturesVar.writer.contramap[Vector[ImageData]](_.map(_.dataUrl))
//        )
//      )
      MovieDisplay.testElement()
    }

    div(
      "computer",
      onMountBind { ctx =>
        given Owner = ctx.owner
        EventStream.fromFuture(websocket.open) --> Observer.empty
      },
      onUnmountCallback(_ => websocket.close()),

      websocket.openEvents
        .mapTo(
          ComputerMessage.AskOffer()
        ) --> websocket.outWriter,

      websocket.inEvents.collect { case ComputerMessage.PictureData(dataUrl) =>
        dataUrl
      } --> picturesVar.updater[String](_ :+ _),

      websocket.inEvents
        .collect { case ComputerMessage.WebRTCToComputerWrapper(message) =>
          message
        }
        .collect { case WebRTCCommProtocol.ForwardWillSendOffer(providerId) =>
          Some(providerId)
        } --> currentProviderIdVar.writer,

      child.maybe <-- currentProviderIdVar.signal.map(
        _.map(id =>
          componentFromOffer(
            id,
            websocket.outWriter,
            websocket.inEvents.collect { case ComputerMessage.WebRTCToComputerWrapper(message) =>
              message
            }
          )
        )
      ),

      hr(),

      div(
        h1("Pictures!"),
        div(
          Button(
            "Take picture",
            _.events.onClick.preventDefault.mapToUnit --> askPictureBus.writer,
            askPictureBus.events
              .sample(currentProviderIdVar.signal)
              .collect { case Some(providerId) =>
                providerId
              }
              .map(ComputerMessage.AskPicture(_)) --> websocket.outWriter,
            disabled <-- websocket.isOpenSignal.invert
          ),
          Button(
            "Make movie!",
            _.events.onClick.preventDefault.mapToUnit --> Observer[Unit] { _ =>
              val data = picturesVar.now()
              utils.videoencoding.encodeToVideo(data.toJSArray, 1).onComplete {
                case Failure(exception) => throw exception
                case Success(arrayBuff) =>
                  org.scalajs.dom.console.log(arrayBuff)
                  val blob = dom.Blob(
                    js.Array(arrayBuff),
                    new BlobPropertyBag {
                      `type` = "video/webm"
                    }
                  )
                  val url = dom.URL.createObjectURL(blob)

                  val link = dom.document
                    .createElement("a")
                    .asInstanceOf[dom.html.Anchor]

                  link.href = url
                  link.download = "stop-motion.webm"
                  link.click()

                  dom.URL.revokeObjectURL(url)
              }
            },
            _.disabled <-- picturesVar.signal.map(_.isEmpty)
          )
        ),
        movieDisplay
        // MovieDisplay.testElement()
      )
    )
  }

  private def componentFromOffer(
      providerId: java.util.UUID,
      socketWriter: Observer[ComputerMessage.ComputerToServerMessage],
      socketMessages: EventStream[WebRTCCommProtocol.ServerToConsumer]
  )(using ExecutionContext): HtmlElement = {
    println(s"Provider id $providerId")
    val webRTCConnection =
      WebRTCConnection.Consumer(socketWriter.contramap(ComputerMessage.WebRTCToServerWrapper(_)), providerId)

    div(
      child.maybe <-- webRTCConnection.videoStreamSignal.map(_.map { stream =>
        videoTag(
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
