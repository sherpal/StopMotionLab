package phone

import com.raquo.laminar.api.L.*
import communication.PhoneMessage
import communication.webrtc.WebRTCCommProtocol
import data.movie.Movie
import org.scalajs.dom
import org.scalajs.dom.{ImageCapture, MediaStream, MediaStreamConstraints}
import services.ImagesService
import urldsl.language.dummyErrorImpl.*
import utils.webrtc.WebRTCConnection
import utils.websocket.JsonWebSocket

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.JavaScriptException
import scala.util.{Failure, Success}

object PhoneApp {

  def apply(
      editorId: String
  )(using imagesService: ImagesService)(using ExecutionContext): HtmlElement = {
    val websocket =
      JsonWebSocket.withPathValue[PhoneMessage.ServerToPhoneMessage, PhoneMessage.PhoneToServerMessage, String](
        root / "image-provider-connection" / segment[String],
        editorId
      )

    val videoStreamVar = Var(Option.empty[MediaStream])

    val webRTCConnectionsVar = Var(Vector.empty[WebRTCConnection.Provider])

    val takePictureBus = new EventBus[(String, Movie.Id, Long)]

    extension (blob: dom.Blob) {
      def extractDataUrl: Future[String] = {
        val promise = Promise[String]()
        val reader  = dom.FileReader()
        reader.onload = { _ =>
          val dataUrl = reader.result.asInstanceOf[String]
          promise.success(dataUrl)
        }
        reader.onerror = { event =>
          promise.failure(JavaScriptException(event))
        }
        reader.readAsDataURL(blob)
        promise.future
      }
    }

    div(
      "phone",

      websocket.inEvents.collect { case PhoneMessage.ComputerAskedPicture(computerId, movieId, requestId) =>
        (computerId, movieId, requestId)
      } --> takePictureBus.writer,
      takePictureBus.events
        .flatMapSwitch { (computerId, movieId, requestId) =>
          EventStream.fromFuture(for {
            stream <- dom.window.navigator.mediaDevices
              .getUserMedia(new MediaStreamConstraints {
                video = true
              })
              .toFuture
            track        = stream.getVideoTracks().head
            imageCapture = ImageCapture(track)
            blob <- imageCapture.takePhoto().toFuture
            _    <- imagesService.postImage(movieId, computerId, blob)
            _ = websocket.outWriter.onNext(PhoneMessage.UploadedPictureFor(computerId, movieId, requestId))
          } yield ())
        } --> Observer.empty,

      websocket.inEvents.collect { case PhoneMessage.WebRTCToPhoneWrapper(WebRTCCommProtocol.ForwardAskOffer(id)) =>
        val provider = WebRTCConnection
          .Provider(videoStreamVar.now().get, websocket.outWriter.contramap(PhoneMessage.WebRTCToServerWrapper(_)), id)
        provider.init().onComplete {
          case Failure(exception) => throw exception
          case Success(())        => println(s"Provider for $id initialized.")
        }
        provider
      } --> webRTCConnectionsVar.updater[WebRTCConnection.Provider](_ :+ _),
      websocket.inEvents
        .collect { case PhoneMessage.WebRTCToPhoneWrapper(message) =>
          message
        }
        .withCurrentValueOf(webRTCConnectionsVar.signal) --> Observer[
        (WebRTCCommProtocol.ServerToProvider, Vector[WebRTCConnection.Provider])
      ] { (message, providers) =>
        providers.foreach(_.inMessagesObserver.onNext(message))
      },

      EventStream
        .periodic(20000)
        .sample(websocket.closedSignal)
        .filterNot(identity)
        .mapTo(PhoneMessage.HeartBeat) --> websocket.outWriter,

      videoTag(
        onMountCallback { el =>
          given Owner = el.owner
          dom.window.navigator.mediaDevices
            .getUserMedia(new MediaStreamConstraints {
              video = true
              audio = false
            })
            .toFuture
            .onComplete {
              case Failure(exception) => throw exception
              case Success(stream)    =>
                el.thisNode.ref.srcObject = stream
                el.thisNode.ref.play()

                videoStreamVar.set(Some(stream))

                websocket.open.onComplete {
                  case Failure(exception) => throw exception
                  case Success(())        => ()
                }

            }
        }
      )
    )
  }

}
