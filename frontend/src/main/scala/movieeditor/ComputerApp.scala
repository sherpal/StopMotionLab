package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{BarDesign, IconName, TagDesign}
import be.doeraene.webcomponents.ui5.{Bar, Button, Icon, Tag}
import com.raquo.laminar.api.L.*
import communication.webrtc.WebRTCCommProtocol
import communication.ComputerMessage
import components.{ModifiableTitle, Router, base}
import data.movie.Movie
import data.movie.Movie.ImageDataWithOrdering
import services.{ImagesService, MoviesService}
import utils.websocket.JsonWebSocket
import urldsl.language.dummyErrorImpl.*

import scala.concurrent.ExecutionContext

object ComputerApp {

  def apply(
      movieId: Movie.Id
  )(using movieService: MoviesService, imagesService: ImagesService)(using ExecutionContext): HtmlElement = {

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

    // when true, send every second the "take picture" message to the provider
    val burstActive = Var(false)

    // true while a "make movie" export is being encoded, so the UI can show that something is happening
    val encodingVar = Var(false)

    val askPictureBus          = new EventBus[Unit]
    var lastPictureRequestId   = -1L
    def nextPictureRequestId() = {
      lastPictureRequestId += 1L
      lastPictureRequestId
    }
    val askPictureEvents = askPictureBus.events
      .sample(currentProviderIdVar.signal)
      .collect { case Some(providerId) =>
        providerId
      }
      .map(ComputerMessage.AskPicture(_, nextPictureRequestId()))
    val inFlightPictureRequests = Var(Set.empty[Long])

    // Only worth showing the QR code while nobody is connected yet.
    val showQrIdSignal: Signal[Option[String]] =
      editorIdVar.signal.combineWithFn(currentProviderIdVar.signal)((id, provider) =>
        if provider.isEmpty then id else None
      )

    def statusTag: HtmlElement =
      Tag.of(
        _.design <-- websocket.isOpenSignal.map(if _ then TagDesign.Positive else TagDesign.Negative),
        _.slots.icon := Icon.of(
          _.name <-- websocket.isOpenSignal.map(if _ then IconName.connected else IconName.disconnected)
        ),
        _ => child.text <-- websocket.isOpenSignal.map(if _ then "Connecté" else "Connexion…")
      )

    def headerBar: HtmlElement =
      Bar.of(
        _.design             := BarDesign.Header,
        _.slots.startContent := Button.of(
          _.iconOnly := true,
          _.icon     := IconName.home,
          _.tooltip  := "Retour à l'accueil",
          _.events.onClick.mapToUnit --> Observer[Unit](_ =>
            Router.router.moveTo("/" ++ (base / entry.DefinedRoutes.home).createPath())
          )
        ),
        _ =>
          ModifiableTitle.h3(
            movieVar.signal.map(_.name),
            Observer[String](newName => movieService.sendCommand(movieId, Movie.Command.ChangeName(newName, _)))
              .filter(_.nonEmpty)
              .contramap[String](_.trim)
          ),
        _.slots.endContent := div(
          display.flex,
          alignItems.center,
          gap.px := 8,
          statusTag,
          DeleteMovieDialog(movieId, movieVar.signal.map(_.name))
        )
      )

    div(
      display <-- initiallyLoaded.map(if _ then "block" else "none"),
      padding.px := 16,
      boxSizing.borderBox,
      display.flex,
      flexDirection.column,
      gap.px          := 16,
      backgroundColor := "var(--sapBackgroundColor, transparent)",

      headerBar,

      onMountBind { ctx =>
        given Owner = ctx.owner
        EventStream.fromFuture(websocket.open) --> Observer.empty
      },
      onUnmountCallback(_ => websocket.close()),

      websocket.inEvents.collect { case ComputerMessage.ThisIsYourId(id) =>
        Some(id)
      } --> editorIdVar.writer,

      websocket.inEvents
        .collect { case ComputerMessage.WebRTCToComputerWrapper(message) =>
          message
        }
        .collect { case WebRTCCommProtocol.ForwardWillSendOffer(providerId) =>
          Some(providerId)
        } --> currentProviderIdVar.writer,

      // The provider (phone) disconnected: if it's the one currently shown, reset to None so the UI drops the
      // (now-dead) video feed and shows the QR code again instead of a frozen preview. Guarded by an identity
      // check so a late/stale notification can't clobber a different provider that has since reconnected.
      websocket.inEvents
        .collect { case ComputerMessage.WebRTCToComputerWrapper(WebRTCCommProtocol.OfferClosed(providerId)) =>
          providerId
        }
        .withCurrentValueOf(currentProviderIdVar.signal)
        .collect { case (closedId, Some(currentId)) if closedId == currentId => Option.empty[String] }
        --> currentProviderIdVar.writer,

      askPictureEvents --> websocket.outWriter,
      askPictureEvents.map(_.requestId) --> inFlightPictureRequests.updater[Long](_ + _),
      websocket.inEvents.collect { case ComputerMessage.PhoneTookPicture(requestId) =>
        requestId
      } --> inFlightPictureRequests.updater[Long](_ - _),
      inFlightPictureRequests.signal.changes
        .filter(_.nonEmpty)
        .flatMapSwitch(_ => EventStream.fromFuture(utils.sleep(3000)))
        .mapTo(Set.empty[Long]) --> inFlightPictureRequests.writer,

      div(
        display.flex,
        flexWrap.wrap,
        gap.px := 16,
        div(flex := "1 1 320px", ConnectCameraCard(showQrIdSignal, currentProviderIdVar.signal)),
        div(
          flex := "1 1 320px",
          CameraPreviewCard(
            currentProviderIdVar.signal,
            websocket.outWriter,
            websocket.inEvents.collect { case ComputerMessage.WebRTCToComputerWrapper(message) =>
              message
            }
          )
        )
      ),

      CaptureActionsCard(
        movieVar,
        websocket.isOpenSignal,
        burstActive,
        encodingVar,
        inFlightPictureRequests.signal,
        askPictureBus.writer
      ),

      // Plain div on purpose: ui5-busy-indicator's own shadow DOM doesn't constrain slotted content to its host's
      // width (its host box can be capped while the slotted content still overflows it), which is exactly what
      // let the storyboard grow unbounded before. A plain block div has no such surprise, so the encoding spinner
      // now lives next to the "Créer le film" button instead of wrapping the whole storyboard.
      div(
        width.percent := 100,
        MovieDisplay(
          movieId,
          movieVar.signal
            .map(_.images.sorted.collect { case ImageDataWithOrdering(imageData, Some(_)) => imageData })
        )
      ),

      onUnmountCallback(_ => updatesCancellation()),
      updateSubscription --> movieVar.writer
    )
  }

}
