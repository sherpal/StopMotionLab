package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{BarDesign, ButtonDesign, IconName}
import be.doeraene.webcomponents.ui5.{Bar, Button, Card, Title}
import com.raquo.laminar.api.L.*
import components.{MovieList, Router, base}
import data.movie.{Movie, MovieMetadata}
import services.{ImagesService, MoviesService}

import scala.concurrent.ExecutionContext

object Home {

  def apply()(using imagesService: ImagesService, moviesService: MoviesService)(using ExecutionContext): HtmlElement = {

    val moviesVar  = Var(Vector.empty[MovieMetadata])
    val loadingVar = Var(true)

    val createMovieBus           = new EventBus[Unit]
    val deleteMovieBus           = new EventBus[Movie.Id]
    val movieProjectionUpdateBus = new EventBus[Int]

    def fetchMovies(): EventStream[Vector[MovieMetadata]] = EventStream.fromFuture(moviesService.movies)

    div(
      padding.px := 16,
      boxSizing.borderBox,
      display.flex,
      flexDirection.column,
      gap.px := 16,

      Bar.of(
        _.design := BarDesign.Header,
        _ => Title.h1("Movie Editor"),
        _.slots.endContent := div(
          display.flex,
          alignItems.center,
          gap.px := 8,
          RestoreMovieDialog(),
          Button.of(
            _.icon := IconName.add,
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
          _.subtitleText <-- moviesVar.signal.map(movies =>
            s"${movies.length} film${if movies.length > 1 then "s" else ""}"
          )
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
        .flatMapSwitch(_ => fetchMovies()) --> moviesVar.writer,

      moviesService.subscribeToMoviesProjection(movieProjectionUpdateBus.writer),
      movieProjectionUpdateBus.events.flatMapSwitch(_ => fetchMovies()) --> moviesVar.writer
    )

  }

}
