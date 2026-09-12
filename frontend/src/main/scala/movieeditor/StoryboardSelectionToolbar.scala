package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{ButtonDesign, IconName, TagDesign}
import be.doeraene.webcomponents.ui5.{Button, Dialog, Tag}
import com.raquo.laminar.api.L.*
import data.images.ImageData
import data.movie.Movie
import data.movie.Movie.MoveDirection
import services.MoviesService

import scala.concurrent.{ExecutionContext, Future}

/** The toolbar above the storyboard's filmstrip: shows how many frames are selected and lets the user delete,
  * duplicate, or move that selection.
  */
object StoryboardSelectionToolbar {

  def apply(
      movieId: Movie.Id,
      selectedIndicesVar: Var[Set[Int]],
      imagesSignal: Signal[Vector[ImageData]]
  )(using movieService: MoviesService, undoStack: UndoStack)(using ExecutionContext): HtmlElement = {
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
        {
          val toRemoveStream: EventStream[Vector[(ImageData.Id, Int)]] = deleteSelectedEvents
            .sample(selectedIndicesVar.signal)
            .withCurrentValueOf(imagesSignal)
            .map((selected, images) =>
              images.zipWithIndex.collect {
                case (image, index) if selected.contains(index) => image.id -> index
              }
            )

          val removedStream: EventStream[Vector[(ImageData.Id, Int)]] = toRemoveStream
            .flatMapSwitch { toRemove =>
              undoStack.markLocalChange()
              EventStream
                .fromFuture(movieService.sendCommand(movieId, command = Movie.Command.RemoveImages(toRemove, _)))
                .collect { case Some(true) => toRemove }
            }

          Vector[Mod[HtmlElement]](
            removedStream.mapToUnit --> selectedIndicesVar.writer.contramap[Unit](_ => Set.empty),
            removedStream --> Observer[Vector[(ImageData.Id, Int)]] { toRemove =>
              undoStack.push(
                UndoAction(
                  s"la suppression de ${toRemove.size} image${if toRemove.size > 1 then "s" else ""}",
                  // Best effort: the images come back, but appended at the end rather than at their former position.
                  () =>
                    Future
                      .sequence(toRemove.map { case (imageId, _) =>
                        movieService.sendCommand(movieId, Movie.Command.AddImage(imageId, _))
                      })
                      .map(_.forall(_.contains(true)))
                )
              )
            }
          )
        }
      )
    }

    def undoButton: HtmlElement =
      Button.of(
        _.icon := IconName.undo,
        _ => "Annuler",
        _.disabled <-- undoStack.canUndoSignal.invert,
        _.tooltip <-- undoStack.signal.map(_.headOption.fold("Rien à annuler")(action => s"Annuler ${action.label}")),
        _.events.onClick.preventDefault.mapToUnit --> Observer[Unit](_ => undoStack.undo())
      )

    def duplicateMods: Mod[HtmlElement] = {
      val duplicateSelectedBus = new EventBus[Unit]

      // For every image that gets duplicated, the event handler slots its copy right after it and then reindexes
      // everything densely from 0 -- see `Movie.Event.ImagesDuplicated`. Replaying that same walk here (over the
      // pre-command ordering we already have on hand) tells us exactly where each copy will land, with no need to
      // wait for the updated movie to come back over the subscription.
      def duplicateCopyIndices(
          orderedBeforeCommand: Vector[(ImageData.Id, Int)],
          toDuplicate: Set[(ImageData.Id, Int)]
      ): Vector[(ImageData.Id, Int)] = {
        var nextIndex = 0
        orderedBeforeCommand.flatMap { case (id, index) =>
          if toDuplicate.contains(id -> index) then {
            val copyIndex = nextIndex + 1
            nextIndex += 2
            Some(id -> copyIndex)
          } else {
            nextIndex += 1
            None
          }
        }
      }

      val requestStream: EventStream[(Vector[(ImageData.Id, Int)], Vector[(ImageData.Id, Int)])] =
        duplicateSelectedBus.events
          .sample(selectedIndicesVar.signal, imagesSignal)
          .map { (selected, images) =>
            val ordered      = images.map(_.id).zipWithIndex
            val toDuplicate  = ordered.filter((_, index) => selected.contains(index))
            ordered -> toDuplicate
          }

      Vector[Mod[HtmlElement]](
        Button.of(
          _.disabled <-- noSelectionSignal,
          _.icon := IconName.duplicate,
          _ => "Dupliquer",
          _.events.onClick.mapToUnit --> duplicateSelectedBus.writer
        ),
        requestStream
          .flatMapSwitch { case (ordered, toDuplicate) =>
            undoStack.markLocalChange()
            EventStream
              .fromFuture(movieService.sendCommand(movieId, Movie.Command.DuplicateImages(toDuplicate, _)))
              .collect { case Some(true) => ordered -> toDuplicate }
          } --> Observer[(Vector[(ImageData.Id, Int)], Vector[(ImageData.Id, Int)])] { case (ordered, toDuplicate) =>
          val copies = duplicateCopyIndices(ordered, toDuplicate.toSet)
          undoStack.push(
            UndoAction(
              s"la duplication de ${toDuplicate.size} image${if toDuplicate.size > 1 then "s" else ""}",
              () => movieService.sendCommand(movieId, Movie.Command.RemoveImages(copies, _)).map(_.contains(true))
            )
          )
        }
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
        {
          val moveSuccessStream = maybeMoveSelectedEvents
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
            .flatMapSwitch { (move, selected, images) =>
              undoStack.markLocalChange()
              EventStream
                .fromFuture(
                  movieService.sendCommand(movieId, Movie.Command.MoveImageRange(move, selected.min, selected.max, _))
                )
                .collect { case Some(true) =>
                  (move, selected, images)
                }
            }

          Vector[Mod[HtmlElement]](
            moveSuccessStream --> Observer.combine[(MoveDirection, Set[Int], Vector[ImageData])](
              selectedIndicesVar
                .updater((selected, move) =>
                  move match {
                    case MoveDirection.Left  => selected.map(_ - 1)
                    case MoveDirection.Right => selected.map(_ + 1)
                  }
                )
                .contramap(_._1)
            ),
            moveSuccessStream --> Observer[(MoveDirection, Set[Int], Vector[ImageData])] { case (move, selected, _) =>
              // A left-move of [min,max] lands that range at [min-1,max-1] -- undoing it is a right-move of
              // exactly that new range, and symmetrically for a right-move. See `Movie.Event.RangeMoved`.
              val (reverseDirection, newMin, newMax) = move match {
                case MoveDirection.Left  => (MoveDirection.Right, selected.min - 1, selected.max - 1)
                case MoveDirection.Right => (MoveDirection.Left, selected.min + 1, selected.max + 1)
              }
              undoStack.push(
                UndoAction(
                  "le déplacement d'images",
                  () =>
                    movieService
                      .sendCommand(movieId, Movie.Command.MoveImageRange(reverseDirection, newMin, newMax, _))
                      .map(_.contains(true))
                )
              )
            }
          )
        }
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
        undoButton,
        duplicateMods,
        moveMods
      )
    )
  }

}
