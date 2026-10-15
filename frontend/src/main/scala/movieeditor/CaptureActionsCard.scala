package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{ButtonDesign, IconName, MessageStripDesign}
import be.doeraene.webcomponents.ui5.{
  Bar,
  BusyIndicator,
  Button,
  Card,
  Dialog,
  Icon,
  MessageStrip,
  ProgressIndicator,
  Text
}
import com.raquo.laminar.api.L.*
import data.movie.Movie
import data.video.VideoEncodingJob
import org.scalajs.dom
import services.MoviesService

import java.io.{PrintWriter, StringWriter}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** The "Prise de vue" card: lets the user ask for a single picture, toggle a one-picture-per-second burst, and export
  * the current frames as a downloadable mp4 video (encoded by the server).
  */
object CaptureActionsCard {

  def apply(
      movieVar: Var[Movie],
      isOpenSignal: Signal[Boolean],
      burstActive: Var[Boolean],
      encodingVar: Var[Boolean],
      inFlightPictureRequestsSignal: Signal[Set[Long]],
      askPictureObserver: Observer[Unit],
      imagesPerSecondSignal: Signal[Int]
  )(using moviesService: MoviesService)(using ExecutionContext): HtmlElement = {
    val createMovieBus = new EventBus[Unit]

    val errorInMovieCreationBus = new EventBus[Throwable]
    val closeErrorDialogBus     = new EventBus[Unit]

    // last known status of the encoding job, None until the server has answered
    val encodingStatusVar = Var(Option.empty[VideoEncodingJob.Status])

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
              _.events.onClick.preventDefault.mapToUnit --> createMovieBus.writer,
              _ =>
                createMovieBus.events.sample(imagesPerSecondSignal) --> Observer[Int](
                  downloadAsVideo(movieVar, encodingVar, encodingStatusVar, _, errorInMovieCreationBus.writer)
                )
            ),
            child.maybe <-- encodingVar.signal.map(Option.when(_)(encodingProgress(encodingStatusVar.signal))),
            Button.of(
              _ =>
                child <-- burstActive.signal.map(if _ then
                  span(Icon.of(_.name := IconName.stop, _ => marginRight := "0.5em"), "Arrêter photos")
                else span(Icon.of(_.name := IconName.record, _ => marginRight := "0.5em"), "Photos en continu")),
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
        ),
      _ =>
        Dialog.of(
          _.showFromEvents(errorInMovieCreationBus.events.mapToUnit),
          _.closeFromEvents(closeErrorDialogBus.events),
          _.headerText := "Erreur lors de la création du film",
          _ =>
            MessageStrip.of(
              _.design := MessageStripDesign.Negative,
              _ =>
                div(
                  p(
                    "Il y a eu une erreur à la création du film: ",
                    child.text <-- errorInMovieCreationBus.events.map(throwable =>
                      Option(throwable.getMessage).getOrElse("Unknown error")
                    )
                  ),
                  pre(
                    width.percent := 100,
                    overflowX.auto,
                    child.text <-- errorInMovieCreationBus.events.map { throwable =>
                      val sw = StringWriter()
                      val pw = PrintWriter(sw)
                      throwable.printStackTrace(pw)
                      val sStackTrace = sw.toString
                      sStackTrace
                    }
                  )
                )
            ),
          _.slots.footer := Bar.of(
            _.slots.endContent := Button.of(
              _ => "Close",
              _.events.onClick.mapToUnit --> closeErrorDialogBus.writer
            )
          )
        )
    )
  }

  private def encodingProgress(statusSignal: Signal[Option[VideoEncodingJob.Status]]): HtmlElement = {
    val runningSignal = statusSignal.map {
      case Some(VideoEncodingJob.Status.Running(encodedFrames, totalFrames)) => Some((encodedFrames, totalFrames))
      case _                                                                 => None
    }

    div(
      child <-- runningSignal.map(_.isDefined).distinct.map {
        case true =>
          // only (re-)created when encoding starts, so that it smoothly moves forward afterwards
          val progressSignal = runningSignal.map(_.getOrElse((0, 1)))
          ProgressIndicator.of(
            _ => width.px := 280,
            _.value <-- progressSignal.map((encoded, total) => encoded * 100 / total),
            _.displayValue <-- progressSignal.map((encoded, total) => s"Encodage : $encoded / $total images")
          )
        case false =>
          div(
            display.flex,
            alignItems.center,
            gap.px := 8,
            BusyIndicator.of(_.active := true),
            Text(child.text <-- statusSignal.map {
              case Some(VideoEncodingJob.Status.Queued(position)) =>
                s"En attente d'un autre film (position $position)…"
              case _ => "Préparation de l'encodage…"
            })
          )
      }
    )
  }

  private def downloadAsVideo(
      movieVar: Var[Movie],
      encodingVar: Var[Boolean],
      encodingStatusVar: Var[Option[VideoEncodingJob.Status]],
      imagesPerSecond: Int,
      errorInMovieCreationObserver: Observer[Throwable]
  )(using
      moviesService: MoviesService
  )(using ExecutionContext): Unit = {
    encodingStatusVar.set(None)
    encodingVar.set(true)
    (for {
      jobId <- moviesService.startVideoEncoding(movieVar.now().id, imagesPerSecond)
      _     <- moviesService.waitForVideo(jobId, status => encodingStatusVar.set(Some(status)))
    } yield jobId).onComplete {
      case Failure(exception) =>
        encodingVar.set(false)
        errorInMovieCreationObserver.onNext(exception)
        throw exception
      case Success(jobId) =>
        encodingVar.set(false)
        // A plain link to the server's file: the browser streams it to disk, without holding it in memory. The file's
        // name comes from the response's Content-Disposition header; `download` makes sure we never navigate away.
        val link = dom.document.createElement("a").asInstanceOf[dom.html.Anchor]
        link.href = moviesService.videoUrl(jobId)
        link.setAttribute("download", "")
        link.click()
    }
  }

}
