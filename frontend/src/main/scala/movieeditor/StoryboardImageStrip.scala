package movieeditor

import be.doeraene.webcomponents.ui5.{Label, Slider, StepInput, ToggleButton}
import be.doeraene.webcomponents.ui5.configkeys.IconName
import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import data.images.ImageData
import org.scalajs.dom
import services.ImagesService

import scala.scalajs.js

/** The scrollable strip of thumbnails at the heart of the storyboard, with the scrollbar and play/stop controls
  * used to move through it. Clicking a thumbnail selects it (plain click), extends the selection (shift-click),
  * or toggles it (ctrl-click); `selectedIndicesVar` is shared with the [[StoryboardSelectionToolbar]] and
  * `scrollPositionVar` with the [[StoryboardBigImageDisplay]].
  */
object StoryboardImageStrip {

  def apply(
      imagesSignal: Signal[Vector[ImageData]],
      selectedIndicesVar: Var[Set[Int]],
      scrollPositionVar: Var[Int]
  )(using imagesService: ImagesService): HtmlElement = {
    val screenResizeBus = new EventBus[Unit]

    def toggleSelectIndexObserver: Observer[Int] =
      selectedIndicesVar.updater((indices, index) =>
        if indices.contains(index) then indices - index else indices + index
      )

    def imageClickObserver(index: Int) = Observer[Set[ClickModifier]] { modifiers =>
      if modifiers.isEmpty then
        selectedIndicesVar.update(current => if current == Set(index) then Set.empty else Set(index))
      else if modifiers.contains(ClickModifier.Control) then toggleSelectIndexObserver.onNext(index)
      else if modifiers.contains(ClickModifier.Shift) then {
        selectedIndicesVar.update { current =>
          (for {
            // both are empty or defined at the same time
            min <- current.minOption
            max <- current.maxOption

          } yield {
            if index < min then current ++ (index to min).toSet
            else if index > max then current ++ (max to index).toSet
            else if current.contains(index) then current - index
            else (min to max).toSet
          }).getOrElse(Set(index))
        }
      }
    }

    div(
      display.flex,
      flexDirection.column,
      gap.px := 16,

      div(
        className       := "smlab-filmstrip-box",
        borderRadius.px := 8,
        width.percent   := 100,
        overflow.hidden,
        backgroundColor := "var(--sapList_Background, transparent)",
        div(
          className := "smlab-filmstrip-track",
          display.flex,
          gap.px     := 8,
          padding.px := 4,
          width      := "max-content",
          children <-- imagesSignal
            .combineWith(selectedIndicesVar.signal)
            .map((images, selected) =>
              images.zipWithIndex
                .map((image, index) =>
                  displayImage(index, image, selected.contains(index), imageClickObserver(index))
                )
            ),
          onMountBind { ctx =>
            val el     = ctx.thisNode.ref
            val parent = el.parentElement

            def containerWidth = parent.getBoundingClientRect().width
            def stripWidth     = el.getBoundingClientRect().width

            def maxScroll = (stripWidth - containerWidth).max(0.0)

            transform <-- scrollPositionVar.signal
              .combineWithFn(imagesSignal.map(_.length))((scroll, imageCount) =>
                (scroll * 1.0 / imageCount.max(1)).min(1.0)
              )
              .map(scroll => s"translateX(-${scroll * maxScroll}px)")
          },
          screenResizeBus.events
            .delay()
            .debounce(25)
            .sample(scrollPositionVar.signal) --> scrollPositionVar.writer
        )
      ),
      scrollBar(scrollPositionVar, imagesSignal.map(_.length)),
      playStopButtons(
        scrollPositionVar.updater[Unit]((current, _) => current + 1),
        scrollPositionVar.signal.combineWithFn(imagesSignal.map(_.length))(_ >= _)
      ),
      onMountUnmountCallbackWithState(
        { _ =>
          val listener: js.Function1[dom.UIEvent, Any] = _ => screenResizeBus.writer.onNext(())
          dom.document.defaultView.addEventListener("resize", listener)
          listener
        },
        { (_, maybeListener) =>
          maybeListener.foreach(dom.document.defaultView.removeEventListener("resize", _))
        }
      )
    )
  }

  private def playStopButtons(nextImageObserver: Observer[Unit], endReachedSignal: Signal[Boolean]): HtmlElement = {
    val imagesPerSecond = Var(1)
    val imagesRate      = imagesPerSecond.signal.map(1.0 / _).distinct
    val playingVar      = Var(false)

    div(
      imagesRate
        .flatMapSwitch(rate => EventStream.periodic((rate * 1000).toInt.max(1)))
        .sample(playingVar.signal)
        .filter(identity)
        .mapToUnit --> nextImageObserver,
      endReachedSignal.changes
        .filter(identity)
        .mapTo(false) --> playingVar.writer,
      display.flex,
      alignItems.center,
      gap.px := 12,
      ToggleButton.of(
        _.pressed <-- playingVar.signal,
        _.icon <-- playingVar.signal.map(if _ then IconName.pause else IconName.play),
        _.tooltip <-- playingVar.signal.map(if _ then "Mettre en pause" else "Lire le film"),
        _.events.onClick.mapToUnit --> playingVar.invertWriter
      ),
      Label.of(_ => "Images par seconde"),
      StepInput.of(
        _.min  := 1.0,
        _.max  := 24.0,
        _.step := 1.0,
        _.value <-- imagesPerSecond.signal.map(_.toDouble),
        _.events.onChange.map(_.target.value.toInt) --> imagesPerSecond.writer,
        _ => width.px := 70
      )
    )
  }

  private def scrollBar(scrollPositionVar: Var[Int], imageCountSignal: Signal[Int]): HtmlElement = {
    div(
      width.percent := 100,
      Slider.of(
        _.min := 0.0,
        _.max <-- imageCountSignal.map(_ - 1).map(_.toDouble),
        _.step          := 1.0,
        _.showTickmarks := true,
        _.value <-- scrollPositionVar.signal.map(_.toDouble),
        _.events.onInput.map(_.target.value.toInt) --> scrollPositionVar.writer
      )
    )
  }

  private def displayImage(
      index: Int,
      data: ImageData,
      selected: Boolean,
      selectObserver: Observer[Set[ClickModifier]]
  )(using imagesService: ImagesService): HtmlElement = {
    div(
      className                    := "smlab-thumb",
      cls("smlab-thumb--selected") := selected,
      borderRadius.px              := 8,
      border                       := "3px solid transparent",
      overflow.hidden,
      flexShrink := 0.0,
      img(
        src       := imagesService.imageUrl(data),
        height.px := 100,
        width.px  := 100,
        display.block,
        htmlAttr("object-fit", StringAsIsCodec) := "cover"
      ),
      onClick.preventDefault.map(event =>
        Set(Option.when(event.ctrlKey)(ClickModifier.Control), Option.when(event.shiftKey)(ClickModifier.Shift)).flatten
      ) --> selectObserver
    )
  }

  private enum ClickModifier:
    case Shift, Control

}
