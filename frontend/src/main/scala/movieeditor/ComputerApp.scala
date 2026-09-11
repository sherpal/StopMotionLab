package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{
  BarDesign,
  ButtonDesign,
  IconName,
  MessageStripDesign,
  TagDesign,
  ValueState
}
import be.doeraene.webcomponents.ui5.{Bar, Button, BusyIndicator, Card, Dialog, Icon, Input, MessageStrip, Tag, Text}
import com.raquo.laminar.api.L.*
import communication.webrtc.WebRTCCommProtocol
import communication.ComputerMessage
import components.{ModifiableTitle, Router, base}
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

    // noinspection MutatorLikeMethodIsParameterless
    def deleteMovieSection: HtmlElement = {
      val deleteClickBus = new EventBus[Unit]
      val closeDialogBus = new EventBus[Unit]
      val confirmBus     = new EventBus[Unit]
      val typedNameVar   = Var("")

      val matchesSignal: Signal[Boolean] =
        typedNameVar.signal.combineWithFn(movieVar.signal.map(_.name))(_ == _)

      div(
        Button.of(
          _.iconOnly := true,
          _.icon     := IconName.delete,
          _.design   := ButtonDesign.Negative,
          _.tooltip  := "Supprimer ce film",
          _.events.onClick.preventDefault.mapToUnit --> deleteClickBus.writer
        ),
        Dialog.of(
          _.showFromEvents(deleteClickBus.events.mapToUnit),
          _.closeFromEvents(closeDialogBus.events),
          _.headerText := "Supprimer ce film",
          // reset the typed text every time the dialog is (re)opened, so a leftover match from a previous,
          // cancelled attempt can't leave the button armed by accident.
          _ => deleteClickBus.events.mapTo("") --> typedNameVar.writer,
          _ =>
            sectionTag(
              display.flex,
              flexDirection.column,
              gap.px := 12,
              p(
                child.text <-- movieVar.signal.map(m =>
                  s"""Pour confirmer, tape le nom du film ci-dessous : « ${m.name} »"""
                )
              ),
              Input.of(
                _.value <-- typedNameVar.signal,
                _.placeholder <-- movieVar.signal.map(_.name),
                _.valueState <-- matchesSignal.combineWithFn(typedNameVar.signal.map(_.isEmpty))((matches, empty) =>
                  if empty then ValueState.None else if matches then ValueState.Positive else ValueState.Negative
                ),
                _.events.onInput.map(_.target.value) --> typedNameVar.writer
              ),
              p(
                small("You can recover a deleted movie from the home menu.")
              )
            ),
          _.slots.footer := div(
            display.flex,
            alignItems.end,
            gap.px := 8,
            Button.of(
              _.design := ButtonDesign.Negative,
              _.disabled <-- matchesSignal.invert,
              _ => "Supprimer définitivement",
              _.events.onClick.mapToUnit --> Observer.combine(closeDialogBus.writer, confirmBus.writer)
            ),
            Button.of(
              _.design := ButtonDesign.Transparent,
              _ => "Annuler",
              _.events.onClick.mapToUnit --> closeDialogBus.writer
            )
          )
        ),
        confirmBus.events
          .flatMapSwitch(_ => EventStream.fromFuture(movieService.deleteWithRetries(movieId)))
          .collect { case true => () } // delay a bit to let projection have a chance to run
          .delay(300) --> Observer[Unit](_ =>
          Router.router.moveTo("/" ++ (base / entry.DefinedRoutes.home).createPath())
        )
      )
    }

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
          deleteMovieSection
        )
      )

    def connectSection: HtmlElement =
      Card.of(
        // see the comment on the Storyboard card in MovieDisplay: ui5-card is inline-block by default and must
        // be pinned to a block box at 100% width, or it shrink-to-fits its content instead of respecting its
        // flex-basis in the row below.
        _ => display.block,
        _ => width.percent := 100,
        _ => boxSizing.borderBox,
        _.slots.header := Card.header.of(
          _.titleText    := "Connecter une caméra",
          _.subtitleText := "Scanne ce QR code avec ton téléphone"
        ),
        _ =>
          div(
            padding.px := 16,
            display.flex,
            flexDirection.column,
            alignItems.center,
            gap.px       := 12,
            minHeight.px := 200,
            child <-- showQrIdSignal.combineWithFn(currentProviderIdVar.signal) {
              case (Some(id), _) =>
                div(
                  cls("smlab-fade-in"),
                  display.flex,
                  flexDirection.column,
                  alignItems.center,
                  gap.px := 8,
                  img(
                    className := "smlab-framed",
                    widthAttr := 200,
                    src       := s"/api/phone-connect-qrcode?editorId=$id",
                    alt       := "Scan with your phone to connect its camera"
                  ),
                  Text("Ouvre l'appareil photo de ton téléphone et scanne ce code")
                )
              case (None, Some(_)) =>
                MessageStrip.of(
                  _.design          := MessageStripDesign.Positive,
                  _.hideCloseButton := true,
                  _ => "Téléphone connecté !"
                )
              case (None, None) =>
                div(
                  display.flex,
                  alignItems.center,
                  gap.px  := 8,
                  opacity := 0.6,
                  Icon.of(_.name := IconName.disconnected),
                  Text("En attente de la connexion au serveur…")
                )
            }
          )
      )

    def cameraSection: HtmlElement =
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
            child <-- currentProviderIdVar.signal.map {
              case Some(id) =>
                componentFromOffer(
                  id,
                  websocket.outWriter,
                  websocket.inEvents.collect { case ComputerMessage.WebRTCToComputerWrapper(message) =>
                    message
                  }
                )
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

    def actionsSection: HtmlElement =
      Card.of(
        _ => display.block,
        _ => width.percent := 100,
        _ => boxSizing.borderBox,
        _.slots.header := Card.header.of(_.titleText := "Prise de vue"),
        _ =>
          div(
            padding.px := 16,
            display.flex,
            flexDirection.column,
            gap.px := 12,
            child.maybe <-- burstActive.signal.map(
              Option.when(_)(
                MessageStrip.of(
                  _.design          := MessageStripDesign.Information,
                  _.hideCloseButton := true,
                  _ => "📸 Rafale activée — une photo est demandée chaque seconde"
                )
              )
            ),
            div(
              display.flex,
              alignItems.center,
              flexWrap.wrap,
              gap.px := 12,
              Button.of(
                _.icon := IconName.camera,
                _ => "Prendre une photo",
                _.disabled <-- websocket.isOpenSignal.invert.combineWithFn(burstActive.signal)(_ || _),
                _.events.onClick.preventDefault.mapToUnit --> askPictureBus.writer
              ),
              Button.of(
                _.icon := IconName.video,
                _ => "Créer le film",
                _.design := ButtonDesign.Emphasized,
                _.disabled <-- movieVar.signal
                  .map(_.images.isEmpty)
                  .combineWithFn(burstActive.signal.combineWithFn(encodingVar.signal)(_ || _))(_ || _),
                _.events.onClick.preventDefault.mapToUnit --> Observer[Unit] { _ =>
                  encodingVar.set(true)
                  val images = movieVar.now().sortedImages
                  Future
                    .sequence(images.map(image => imagesService.getImageUrlEncoded(image.id)))
                    .map(_.toJSArray)
                    .flatMap(utils.videoencoding.encodeToVideo(_, 1))
                    .onComplete {
                      case Failure(exception) =>
                        encodingVar.set(false)
                        throw exception
                      case Success(arrayBuff) =>
                        encodingVar.set(false)
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
                }
              ),
              child.maybe <-- encodingVar.signal.map(
                Option.when(_)(
                  div(
                    display.flex,
                    alignItems.center,
                    gap.px := 8,
                    BusyIndicator.of(_.active := true),
                    Text("Encodage de la vidéo…")
                  )
                )
              ),
              Button.of(
                _ =>
                  child <-- burstActive.signal.map(if _ then
                    span(Icon.of(_.name := IconName.stop, _ => marginRight := "0.5em"), "Arrêter rafale")
                  else span(Icon.of(_.name := IconName.record, _ => marginRight := "0.5em"), "Démarrer rafale")),
                _.design <-- burstActive.signal.map(if _ then ButtonDesign.Negative else ButtonDesign.Default),
                _.events.onClick.mapToUnit --> burstActive.invertWriter,
                _ =>
                  EventStream
                    .periodic(1000)
                    .sample(burstActive.signal)
                    .filter(identity)
                    .sample(inFlightPictureRequests.signal)
                    .filter(_.isEmpty)
                    .mapToUnit --> askPictureBus.writer
              )
            )
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
        div(flex := "1 1 320px", connectSection),
        div(flex := "1 1 320px", cameraSection)
      ),

      actionsSection,

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

  private def componentFromOffer(
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
