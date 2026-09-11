package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{ButtonDesign, IconName, TagDesign}
import be.doeraene.webcomponents.ui5.{Button, Dialog, Tag}
import com.raquo.laminar.api.L.*
import data.images.ImageData
import data.movie.Movie
import data.movie.Movie.MoveDirection
import services.MoviesService

import scala.concurrent.ExecutionContext

/** The toolbar above the storyboard's filmstrip: shows how many frames are selected and lets the user delete,
  * duplicate, or move that selection.
  */
object StoryboardSelectionToolbar {

  def apply(
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

}
