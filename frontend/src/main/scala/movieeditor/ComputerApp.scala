package movieeditor

import be.doeraene.webcomponents.ui5.Button
import com.raquo.laminar.api.L.*
import communication.webrtc.WebRTCCommProtocol
import communication.ComputerMessage
import data.images.ImageData
import data.movie.Movie
import data.movie.Movie.ImageDataWithOrdering
import org.scalajs.dom
import org.scalajs.dom.BlobPropertyBag
import services.{ImagesService, LocalStorageService, MoviesService}
import utils.websocket.JsonWebSocket
import urldsl.language.dummyErrorImpl.*
import utils.webrtc.WebRTCConnection

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.JSRichIterableOnce
import scala.util.{Failure, Success}

object ComputerApp {

  def apply(
      movieId: Movie.Id
  )(using movieService: MoviesService, imagesService: ImagesService)(using ExecutionContext): HtmlElement = {
    val storage = LocalStorageService()

    val websocket = JsonWebSocket.withPathValue[
      ComputerMessage.ServerToComputerMessage,
      ComputerMessage.ComputerToServerMessage,
      Movie.Id
    ](root / "movie-editor-connection" / segment[Movie.Id], movieId)

    val (updateSubscription, updatesCancellation) = movieService.subscribe(movieId)

    val movieVar: Var[Movie] = Var(Movie.entityInfo.initialState)

    val initiallyLoaded: Signal[Boolean] = movieVar.signal.map(_.id != Movie.Id.dummy)

    val currentProviderIdVar = Var(Option.empty[String])

    // The id this computer session was assigned by the server, once its websocket is open; a phone that scans the
    // QR code built from it can pair with this session.
    val editorIdVar = Var(Option.empty[String])

    val askPictureBus = new EventBus[Unit]

    def movieDisplay = {
      div(
        MovieDisplay(
          movieId,
          movieVar.signal
            .map(_.images.sorted.collect { case ImageDataWithOrdering(imageData, Some(_)) => imageData })
        )
      )
//      MovieDisplay.testElement()
    }

    div(
      display <-- initiallyLoaded.map(if _ then "block" else "none"),
      "movieeditor",
      onMountBind { ctx =>
        given Owner = ctx.owner
        EventStream.fromFuture(websocket.open) --> Observer.empty
      },
      onUnmountCallback(_ => websocket.close()),

      websocket.inEvents.collect { case ComputerMessage.ThisIsYourId(id) =>
        Some(id)
      } --> editorIdVar.writer,

      div(
        h1("Connect a phone as camera"),
        child.maybe <-- editorIdVar.signal.map(
          _.map(id =>
            img(
              src := s"/api/phone-connect-qrcode?editorId=$id",
              alt := "Scan with your phone to connect its camera"
            )
          )
        )
      ),

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
              val images = movieVar.now().sortedImages
              Future
                .sequence(images.map(image => imagesService.getImageUrlEncoded(image.id)))
                .map(_.toJSArray)
                .flatMap(utils.videoencoding.encodeToVideo(_, 1))
                .onComplete {
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
            _.disabled <-- movieVar.signal.map(_.images.isEmpty)
          )
        ),
        movieDisplay
        // MovieDisplay.testElement()
      ),
      onUnmountCallback(_ => updatesCancellation()),
      updateSubscription --> movieVar.writer
    )
  }

  private def componentFromOffer(
      providerId: String,
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
