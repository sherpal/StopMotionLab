package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{ButtonDesign, IconName}
import be.doeraene.webcomponents.ui5.{Button, BusyIndicator, Dialog, Icon, Text}
import com.raquo.laminar.api.L.*
import data.movie.{DeletedMovieMetadata, Movie}
import services.MoviesService

import scala.concurrent.ExecutionContext

/** The icon button + dialog listing recently deleted movies and letting the user restore one. On success, the
  * restored movie is dropped from the local deleted-list right away (rather than re-fetched, since the
  * read-model projection needs a moment to catch up) and reported via `restoredObserver` so the caller can
  * refresh its own movie list.
  */
object RestoreMovieDialog {

  def apply(restoredObserver: Observer[Movie.Id])(using
      moviesService: MoviesService
  )(using ExecutionContext): HtmlElement = {
    val openClickBus      = new EventBus[Unit]
    val closeDialogBus    = new EventBus[Unit]
    val restoreClickBus   = new EventBus[Movie.Id]
    val restoredBus       = new EventBus[Movie.Id]
    val deletedMoviesVar  = Var(Vector.empty[DeletedMovieMetadata])
    val deletedLoadingVar = Var(true)

    def fetchDeleted(): EventStream[Vector[DeletedMovieMetadata]] = EventStream.fromFuture(moviesService.deletedMovies)

    def deletedMovieRow(deletedMovie: DeletedMovieMetadata): HtmlElement =
      div(
        display.flex,
        alignItems.center,
        justifyContent.spaceBetween,
        gap.px       := 12,
        padding.px   := 8,
        borderBottom := "1px solid var(--sapList_BorderColor, #ddd)",
        span(deletedMovie.name),
        Button.of(
          _.icon   := IconName.undo,
          _ => "Restaurer",
          _.design := ButtonDesign.Emphasized,
          _.events.onClick.preventDefault.mapTo(deletedMovie.id) --> restoreClickBus.writer
        )
      )

    div(
      Button.of(
        _.iconOnly := true,
        _.icon     := IconName.history,
        _.tooltip  := "Films supprimés",
        _.events.onClick.preventDefault.mapToUnit --> openClickBus.writer
      ),
      Dialog.of(
        _.showFromEvents(openClickBus.events.mapToUnit),
        _.closeFromEvents(closeDialogBus.events),
        _.headerText := "Films supprimés",
        _ =>
          sectionTag(
            minWidth.px := 360,
            child <-- deletedMoviesVar.signal.combineWithFn(deletedLoadingVar.signal) { (movies, loading) =>
              if loading then
                div(
                  padding.px := 24,
                  display.flex,
                  justifyContent.center,
                  BusyIndicator.of(_.active := true)
                )
              else if movies.isEmpty then
                div(
                  padding.px := 24,
                  display.flex,
                  flexDirection.column,
                  alignItems.center,
                  gap.px  := 8,
                  opacity := 0.6,
                  Icon.of(_.name := IconName.history),
                  Text("Aucun film supprimé pour l'instant.")
                )
              else div(display.flex, flexDirection.column, movies.map(deletedMovieRow))
            }
          ),
        _.slots.footer := div(
          display.flex,
          alignItems.end,
          Button.of(
            _.design := ButtonDesign.Transparent,
            _ => "Fermer",
            _.events.onClick.mapToUnit --> closeDialogBus.writer
          )
        )
      ),
      openClickBus.events.mapTo(true) --> deletedLoadingVar.writer,
      openClickBus.events.flatMapSwitch(_ => fetchDeleted()) --> Observer.combine[Vector[DeletedMovieMetadata]](
        deletedMoviesVar.writer,
        deletedLoadingVar.writer.contramap(_ => false)
      ),
      // Restoring, like deleting, goes through the event-sourced actor first and the read-model projections
      // (deleted_movie / movie tables) catch up asynchronously after -- re-fetching either list right away can
      // race ahead of that and show stale data. So instead of re-fetching the deleted list, drop the restored
      // id from it locally the moment the command succeeds; the caller is responsible for refreshing the
      // active-movies list, since that data isn't already known locally.
      restoreClickBus.events
        .flatMapSwitch(id => EventStream.fromFuture(moviesService.restoreWithRetries(id)).map(id -> _))
        .collect { case (id, true) => id } --> restoredBus.writer,
      restoredBus.events --> deletedMoviesVar.updater[Movie.Id]((movies, id) => movies.filterNot(_.id == id)),
      restoredBus.events --> restoredObserver
    )
  }

}
