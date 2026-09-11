package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{BarDesign, ButtonDesign, IconName}
import be.doeraene.webcomponents.ui5.{Bar, BusyIndicator, Button, Card, Dialog, Icon, Text, Title}
import com.raquo.laminar.api.L.*
import components.{MovieList, Router, base}
import data.movie.{DeletedMovieMetadata, Movie, MovieMetadata}
import services.{ImagesService, MoviesService}

import scala.concurrent.ExecutionContext

object Home {

  def apply()(using imagesService: ImagesService, moviesService: MoviesService)(using ExecutionContext): HtmlElement = {

    val moviesVar  = Var(Vector.empty[MovieMetadata])
    val loadingVar = Var(true)

    val createMovieBus = new EventBus[Unit]
    val deleteMovieBus = new EventBus[Movie.Id]

    def fetchMovies(): EventStream[Vector[MovieMetadata]] = EventStream.fromFuture(moviesService.movies)

    def deletedMoviesSection: HtmlElement = {
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
        // id from it locally the moment the command succeeds; the active-movies list still gets a genuine
        // refetch, since that data isn't already known locally, same as everywhere else this app refreshes it.
        restoreClickBus.events
          .flatMapSwitch(id => EventStream.fromFuture(moviesService.restoreWithRetries(id)).map(id -> _))
          .collect { case (id, true) => id } --> restoredBus.writer,
        restoredBus.events --> deletedMoviesVar.updater[Movie.Id]((movies, id) => movies.filterNot(_.id == id)),
        restoredBus.events.delay(300).flatMapSwitch(_ => fetchMovies()) --> moviesVar.writer
      )
    }

    div(
      padding.px := 16,
      boxSizing.borderBox,
      display.flex,
      flexDirection.column,
      gap.px     := 16,

      Bar.of(
        _.design := BarDesign.Header,
        _ => Title.h1("Movie Editor"),
        _.slots.endContent := div(
          display.flex,
          alignItems.center,
          gap.px := 8,
          deletedMoviesSection,
          Button.of(
            _.icon   := IconName.add,
            _ => "Nouveau film",
            _.design := ButtonDesign.Emphasized,
            _.events.onClick.preventDefault.mapToUnit --> createMovieBus.writer
          )
        )
      ),

      Card.of(
        // see the comment on the storyboard card in MovieDisplay: ui5-card is inline-block by default and must be
        // pinned to a block box at 100% width, or it shrink-to-fits its content instead of filling the page.
        _ => display.block,
        _ => width.percent := 100,
        _ => boxSizing.borderBox,
        _.slots.header := Card.header.of(
          _.titleText := "Mes films",
          _.subtitleText <-- moviesVar.signal.map(movies => s"${movies.length} film${if movies.length > 1 then "s" else ""}")
        ),
        _ => MovieList(moviesVar.signal, loadingVar.signal, deleteMovieBus.writer)
      ),

      fetchMovies() --> Observer.combine[Vector[MovieMetadata]](
        moviesVar.writer,
        loadingVar.writer.contramap(_ => false)
      ),

      createMovieBus.events
        .flatMapSwitch(_ => EventStream.fromFuture(moviesService.create()))
        .map(id => (base / entry.DefinedRoutes.movieEditorPath).createPath(id)) --> Observer[String](path =>
        Router.router.moveTo("/" ++ path)
      ),
      deleteMovieBus.events
        .flatMapSwitch(movieId => EventStream.fromFuture(moviesService.deleteWithRetries(movieId)))
        .flatMapSwitch(_ => fetchMovies()) --> moviesVar.writer
    )

  }

}
