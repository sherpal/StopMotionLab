package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{ButtonDesign, IconName, MessageStripDesign}
import be.doeraene.webcomponents.ui5.{Button, BusyIndicator, Card, Icon, MessageStrip, Text}
import com.raquo.laminar.api.L.*
import data.movie.Movie
import org.scalajs.dom
import org.scalajs.dom.BlobPropertyBag
import services.ImagesService

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.JSRichIterableOnce
import scala.util.{Failure, Success}

/** The "Prise de vue" card: lets the user ask for a single picture, toggle a one-picture-per-second burst, and
  * export the current frames as a downloadable webm video.
  */
object CaptureActionsCard {

  def apply(
      movieVar: Var[Movie],
      isOpenSignal: Signal[Boolean],
      burstActive: Var[Boolean],
      encodingVar: Var[Boolean],
      inFlightPictureRequestsSignal: Signal[Set[Long]],
      askPictureObserver: Observer[Unit]
  )(using imagesService: ImagesService)(using ExecutionContext): HtmlElement =
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
              _.disabled <-- isOpenSignal.invert.combineWithFn(burstActive.signal)(_ || _),
              _.events.onClick.preventDefault.mapToUnit --> askPictureObserver
            ),
            Button.of(
              _.icon := IconName.video,
              _ => "Créer le film",
              _.design := ButtonDesign.Emphasized,
              _.disabled <-- movieVar.signal
                .map(_.images.isEmpty)
                .combineWithFn(burstActive.signal.combineWithFn(encodingVar.signal)(_ || _))(_ || _),
              _.events.onClick.preventDefault.mapToUnit --> Observer[Unit](_ => downloadAsVideo(movieVar, encodingVar))
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
                  .sample(inFlightPictureRequestsSignal)
                  .filter(_.isEmpty)
                  .mapToUnit --> askPictureObserver
            )
          )
        )
    )

  private def downloadAsVideo(movieVar: Var[Movie], encodingVar: Var[Boolean])(using
      imagesService: ImagesService
  )(using ExecutionContext): Unit = {
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

}
