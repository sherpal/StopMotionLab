package phone

import be.doeraene.webcomponents.ui5.configkeys.IconName
import be.doeraene.webcomponents.ui5.{Button, Icon, Text}
import com.raquo.laminar.api.L.*
import communication.PhoneMessage
import communication.webrtc.WebRTCCommProtocol
import data.movie.Movie
import org.scalajs.dom
import org.scalajs.dom.{
  ImageCapture,
  MediaDeviceInfo,
  MediaDeviceKind,
  MediaStream,
  MediaStreamConstraints,
  MediaTrackConstraints
}
import services.ImagesService
import urldsl.language.dummyErrorImpl.*
import utils.webrtc.WebRTCConnection
import utils.websocket.JsonWebSocket

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.JavaScriptException
import scala.scalajs.js.|
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

    // The list of video input devices found on the phone (typically the front and back cameras), and the index
    // in that list of the one currently feeding `videoStreamVar`.
    val camerasVar      = Var(IndexedSeq.empty[MediaDeviceInfo])
    val cameraIndexVar  = Var(0)
    val switchCameraBus = new EventBus[Unit]

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

    def stopStream(streamOpt: Option[MediaStream]): Unit =
      streamOpt.foreach(_.getTracks().foreach(_.stop()))

    /** Starts (or restarts) the camera feed. Passing a `deviceId` pins it to a specific video input device;
      * `None` lets the browser pick whichever one it considers the default.
      */
    def startCamera(deviceId: Option[String]): Future[MediaStream] =
      dom.window.navigator.mediaDevices
        .getUserMedia(new MediaStreamConstraints {
          video = deviceId.fold[Boolean | MediaTrackConstraints](true)(id =>
            new MediaTrackConstraints { this.deviceId = id }
          )
          audio = false
        })
        .toFuture

    /** Best-effort lookup of the device id backing a stream's active video track, so the camera picked by
      * the browser on first load can be located in the device list.
      */
    def currentDeviceIdOf(stream: MediaStream): Option[String] =
      stream.getVideoTracks().headOption.flatMap { track =>
        val deviceId = track.getSettings().asInstanceOf[js.Dynamic].selectDynamic("deviceId")
        if js.isUndefined(deviceId) then None else Some(deviceId.asInstanceOf[String])
      }

    div(
      cls := "smlab-phone-app",

      websocket.inEvents.collect { case PhoneMessage.ComputerAskedPicture(computerId, movieId, requestId) =>
        (computerId, movieId, requestId)
      } --> takePictureBus.writer,
      takePictureBus.events
        .withCurrentValueOf(videoStreamVar.signal)
        .flatMapSwitch { case (computerId, movieId, requestId, maybeStream) =>
          EventStream.fromFuture(for {
            // Reuse the stream currently on screen, so a photo is always taken with the camera the user
            // actually selected instead of whichever one the browser would pick on a fresh request.
            stream <- maybeStream match {
              case Some(stream) => Future.successful(stream)
              case None         => Future.failed(JavaScriptException("No active camera stream"))
            }
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

      switchCameraBus.events
        .flatMapSwitch { _ =>
          val cameras = camerasVar.now()
          if cameras.length < 2 then EventStream.empty
          else {
            val nextIndex    = (cameraIndexVar.now() + 1) % cameras.length
            val nextDeviceId = cameras(nextIndex).deviceId

            // Most phones only let one getUserMedia video capture run at a time: asking for the new camera
            // while the old one is still open makes the browser silently reject (or just hang) the request,
            // which is why switching used to do nothing. So the old camera has to be released first — at
            // the cost of a brief gap in the picture, including on any live WebRTC call, while the new one
            // spins up.
            stopStream(videoStreamVar.now())
            videoStreamVar.set(None)

            EventStream.fromFuture(
              startCamera(Some(nextDeviceId))
                .flatMap { newStream =>
                  cameraIndexVar.set(nextIndex)
                  videoStreamVar.set(Some(newStream))

                  // Push the new track onto every live WebRTC call so connected computers follow the switch
                  // too, instead of only the local preview. A provider whose call already ended just no-ops.
                  Future
                    .sequence(webRTCConnectionsVar.now().map { provider =>
                      provider.replaceTrack(newStream).recover { case exception =>
                        println(s"Failed to replace track on an active call: $exception")
                      }
                    })
                    .map(_ => ())
                }
                .recover { case exception =>
                  println(s"Failed to switch camera: $exception")
                }
            )
          }
        } --> Observer.empty,

      div(
        cls := "smlab-phone-video-wrap",
        onMountCallback { el =>
          given Owner = el.owner
          startCamera(None).onComplete {
            case Failure(exception) => throw exception
            case Success(stream) =>
              videoStreamVar.set(Some(stream))
              val activeDeviceId = currentDeviceIdOf(stream)

              dom.window.navigator.mediaDevices.enumerateDevices().toFuture.onComplete {
                case Failure(exception) => throw exception
                case Success(devices) =>
                  val cameras = devices.filter(_.kind == MediaDeviceKind.videoinput).toIndexedSeq
                  camerasVar.set(cameras)
                  cameraIndexVar.set(
                    activeDeviceId
                      .map(id => cameras.indexWhere(_.deviceId == id))
                      .filter(_ >= 0)
                      .getOrElse(0)
                  )
              }

              websocket.open.onComplete {
                case Failure(exception) => throw exception
                case Success(())        => ()
              }
          }
        },
        child.maybe <-- videoStreamVar.signal.map(_.map { stream =>
          videoTag(
            cls := "smlab-phone-video",
            onMountCallback { ctx =>
              ctx.thisNode.ref.srcObject = stream
              ctx.thisNode.ref.play()
            }
          )
        }),
        child.maybe <-- videoStreamVar.signal.map(streamOpt =>
          Option.when(streamOpt.isEmpty)(
            div(
              cls := "smlab-phone-placeholder",
              Icon.of(_.name := IconName.camera),
              Text("Connexion à la caméra…")
            )
          )
        )
      ),
      div(
        cls := "smlab-phone-controls",
        child.maybe <-- camerasVar.signal.map(cameras =>
          Option.when(cameras.length > 1)(
            Button.of(
              _.icon := IconName.synchronize,
              _ => "Changer de caméra",
              _.events.onClick.preventDefault.mapToUnit --> switchCameraBus.writer
            )
          )
        )
      )
    )
  }

}
