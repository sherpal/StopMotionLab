package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{ButtonDesign, IconName, TagDesign}
import be.doeraene.webcomponents.ui5.{Button, Card, Dialog, Icon, Label, Slider, StepInput, Tag, Text, ToggleButton}
import com.raquo.laminar.api.L.*
import data.images.ImageData
import com.raquo.laminar.codecs.StringAsIsCodec
import data.movie.Movie
import data.movie.Movie.MoveDirection
import org.scalajs.dom
import services.{ImagesService, MoviesService}

import scala.concurrent.ExecutionContext
import scala.scalajs.js

object MovieDisplay {

  /** The goal of this component is to display the work in progress movie.
    */
  def apply(movieId: Movie.Id, imagesSignal: Signal[Vector[ImageData]])(using
      movieService: MoviesService
  )(using
      ImagesService,
      ExecutionContext
  ): HtmlElement = {
    val selectedIndices = Var(Set.empty[Int])

    val screenResizeBus = new EventBus[Unit]

    val mouseMoveBus = new EventBus[dom.MouseEvent]

    val scrollPositionVar = Var(0)

    def toggleSelectIndexObserver: Observer[Int] =
      selectedIndices.updater((indices, index) => if indices.contains(index) then indices - index else indices + index)

    def imageClickObserver(index: Int) = Observer[Set[ClickModifier]] { modifiers =>
      if modifiers.isEmpty then
        selectedIndices.update(current => if current == Set(index) then Set.empty else Set(index))
      else if modifiers.contains(ClickModifier.Control) then toggleSelectIndexObserver.onNext(index)
      else if modifiers.contains(ClickModifier.Shift) then {
        selectedIndices.update { current =>
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

    Card.of(
      // ui5-card defaults to display:inline-block, which shrink-to-fits its content instead of being capped by
      // its container; since the filmstrip below can grow arbitrarily wide, that growth would otherwise propagate
      // straight out to the whole page. Forcing it to a block box pinned to 100% width keeps it capped.
      _ => display.block,
      _ => width.percent := 100,
      _ => boxSizing.borderBox,
      _.slots.header := Card.header.of(
        _.titleText := "Storyboard",
        _.subtitleText <-- imagesSignal.map(images => s"${images.length} image${if images.length > 1 then "s" else ""}")
      ),
      _ =>
        div(
          padding.px := 16,
          display.flex,
          flexDirection.column,
          gap.px := 16,

          manipulateSelectionComponent(movieId, selectedIndices, imagesSignal),

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
                .combineWith(selectedIndices.signal)
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
          displayBigImage(scrollPositionVar.signal, imagesSignal),
          playStopButtons(
            scrollPositionVar.updater[Unit]((current, _) => current + 1),
            scrollPositionVar.signal.combineWithFn(imagesSignal.map(_.length))(_ >= _)
          ),
          onMouseMove --> mouseMoveBus.writer,
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

  private def displayBigImage(scrollPositionSignal: Signal[Int], imagesSignal: Signal[Vector[ImageData]])(using
      imagesService: ImagesService
  ) = {
    val currentImageSignal =
      imagesSignal.combineWithFn(scrollPositionSignal)((images, index) =>
        Option.when(images.nonEmpty)(images(index.min(images.length - 1)))
      )

    val frameLabelSignal =
      imagesSignal
        .map(_.length)
        .combineWithFn(scrollPositionSignal)((count, index) =>
          if count == 0 then None else Some(s"${index.min(count - 1) + 1} / $count")
        )

    div(
      className := "smlab-framed",
      position.relative,
      width.percent := 100,
      minHeight.px  := 300,
      display.flex,
      alignItems.center,
      justifyContent.center,
      backgroundColor := "var(--sapList_Background, #eee)",

      child <-- imagesSignal.map { images =>
        if images.isEmpty then
          div(
            padding.px := 48,
            display.flex,
            flexDirection.column,
            alignItems.center,
            gap.px  := 8,
            opacity := 0.5,
            Icon.of(_.name := IconName.camera),
            Text("En attente d'images...")
          )
        else
          img(
            cls("smlab-fade-in"),
            height.px                               := 500,
            maxWidth.percent                        := 100,
            htmlAttr("object-fit", StringAsIsCodec) := "contain",
            src <-- scrollPositionSignal.map(index => images(index.min(images.length - 1))).map(imagesService.imageUrl)
          )
      },

      child.maybe <-- frameLabelSignal.map(
        _.map(label =>
          Tag.of(
            _.design := TagDesign.Neutral,
            _ => position.absolute,
            _ => top.px  := 8,
            _ => left.px := 8,
            _ => label
          )
        )
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

  private def manipulateSelectionComponent(
      movieId: Movie.Id,
      selectedIndicesVar: Var[Set[Int]],
      imagesSignal: Signal[Vector[ImageData]]
  )(using movieService: MoviesService)(using ExecutionContext): HtmlElement = {
    val atLeastOneSelectedSignal = selectedIndicesVar.signal.map(_.nonEmpty)
    val noSelectionSignal        = atLeastOneSelectedSignal.invert

    def delButtonMods: Mod[HtmlElement] = {
      val deleteClickBus = new EventBus[Unit]
      val closeDialogBus = new EventBus[Unit]

      val deleteSelectedBus = new EventBus[Unit]

      val deleteSelectedEvents = EventStream.merge(
        deleteSelectedBus.events,
        deleteClickBus.events.sample(selectedIndicesVar.signal).filter(_.size == 1).mapToUnit
      )

      Vector[Mod[HtmlElement]](
        Button.of(
          _.disabled <-- noSelectionSignal,
          _.icon := IconName.delete,
          _ => "Supprimer",
          _.design := ButtonDesign.Negative,
          _.events.onClick.mapToUnit --> deleteClickBus.writer
        ),
        Dialog.of(
          _.showFromEvents(deleteClickBus.events.sample(selectedIndicesVar.signal).filter(_.size > 1).mapToUnit),
          _.closeFromEvents(closeDialogBus.events),
          _.headerText := "Supprimer des images",
          _ =>
            sectionTag(
              p(
                child.text <-- selectedIndicesVar.signal
                  .map(_.size)
                  .map(nbrSelected => s"Tu vas supprimer $nbrSelected photos. Tu es sûr de vouloir faire ça?")
              )
            ),
          _.slots.footer := div(
            display.flex,
            alignItems.end,
            gap.px := 8,
            Button.of(
              _.design := ButtonDesign.Negative,
              _ => "Supprimer",
              _.events.onClick.mapToUnit --> Observer.combine(
                closeDialogBus.writer,
                deleteSelectedBus.writer
              )
            ),
            Button.of(
              _.design := ButtonDesign.Transparent,
              _ => "Annuler",
              _.events.onClick.mapToUnit --> closeDialogBus.writer
            )
          )
        ),
        deleteSelectedEvents
          .sample(selectedIndicesVar.signal)
          .withCurrentValueOf(imagesSignal)
          .map((selected, images) =>
            images.zipWithIndex.collect {
              case (image, index) if selected.contains(index) => image.id -> index
            }
          )
          .flatMapSwitch(imageIdsToRemove =>
            EventStream.fromFuture(
              movieService.sendCommand(movieId, command = Movie.Command.RemoveImages(imageIdsToRemove, _))
            )
          )
          .collect { case Some(true) => () } --> selectedIndicesVar.writer.contramap[Any](_ => Set.empty)
      )
    }

    def duplicateMods: Mod[HtmlElement] = {
      val duplicateSelectedBus = new EventBus[Unit]

      Vector[Mod[HtmlElement]](
        Button.of(
          _.disabled <-- noSelectionSignal,
          _.icon := IconName.duplicate,
          _ => "Dupliquer",
          _.events.onClick.mapToUnit --> duplicateSelectedBus.writer
        ),
        duplicateSelectedBus.events
          .sample(selectedIndicesVar.signal, imagesSignal)
          .map((selected, images) => images.map(_.id).zipWithIndex.filter((_, index) => selected.contains(index)))
          .flatMapSwitch(toDuplicate =>
            EventStream.fromFuture(movieService.sendCommand(movieId, Movie.Command.DuplicateImages(toDuplicate, _)))
          ) --> Observer.empty
      )
    }

    def moveMods: Mod[HtmlElement] = {
      val moveSelectedBus = new EventBus[MoveDirection]

      val maybeMoveSelectedEvents = moveSelectedBus.events
        .withCurrentValueOf(selectedIndicesVar.signal)
        .map((move, selected) =>
          Option.when(
            selected.nonEmpty && (selected.min to selected.max).toSet == selected
          )(move)
        )

      val closeDialogBus = new EventBus[Unit]

      Vector[Mod[HtmlElement]](
        Button.of(
          _.disabled <-- noSelectionSignal,
          _.iconOnly := true,
          _.icon     := IconName.`arrow-left`,
          _.tooltip  := "Déplacer vers la gauche",
          _.events.onClick.mapTo(MoveDirection.Left) --> moveSelectedBus.writer
        ),
        Button.of(
          _.disabled <-- noSelectionSignal,
          _.iconOnly := true,
          _.icon     := IconName.`arrow-right`,
          _.tooltip  := "Déplacer vers la droite",
          _.events.onClick.mapTo(MoveDirection.Right) --> moveSelectedBus.writer
        ),
        Dialog.of(
          _.showFromEvents(maybeMoveSelectedEvents.filter(_.isEmpty).mapToUnit),
          _.closeFromEvents(closeDialogBus.events),
          _.headerText := "Tu ne peux pas bouger ça",
          _ =>
            sectionTag(
              "Il ne peut pas y avoir de trous dans ta sélection pour bouger des images."
            ),
          _.slots.footer := div(
            display.flex,
            alignItems.end,
            Button.of(
              _.design := ButtonDesign.Transparent,
              _ => "Annuler",
              _.events.onClick.mapToUnit --> closeDialogBus.writer
            )
          )
        ),
        maybeMoveSelectedEvents
          .collect { case Some(move) =>
            move
          }
          .withCurrentValueOf(selectedIndicesVar.signal, imagesSignal.signal)
          .filter((move, selected, images) =>
            move match {
              case MoveDirection.Left  => !selected.contains(0)
              case MoveDirection.Right => !selected.contains(images.length - 1)
            }
          )
          .flatMapSwitch((move, selected, images) =>
            EventStream
              .fromFuture(
                movieService.sendCommand(movieId, Movie.Command.MoveImageRange(move, selected.min, selected.max, _))
              )
              .collect { case Some(true) =>
                (move, selected, images)
              }
          ) --> Observer.combine[(MoveDirection, Set[Int], Vector[ImageData])](
          selectedIndicesVar
            .updater((selected, move) =>
              move match {
                case MoveDirection.Left  => selected.map(_ - 1)
                case MoveDirection.Right => selected.map(_ + 1)
              }
            )
            .contramap(_._1)
        )
      )
    }

    div(
      display.flex,
      alignItems.center,
      justifyContent.spaceBetween,
      flexWrap.wrap,
      gap.px := 12,
      div(
        minHeight.px := 22,
        child.maybe <-- selectedIndicesVar.signal.map(selected =>
          Option.when(selected.nonEmpty)(
            Tag.of(
              _.design := TagDesign.Information,
              _ => s"${selected.size} sélectionnée${if selected.size > 1 then "s" else ""}"
            )
          )
        )
      ),
      div(
        display.flex,
        gap.px := 8,
        delButtonMods,
        duplicateMods,
        moveMods
      )
    )
  }

  private enum ClickModifier:
    case Shift, Control

}
