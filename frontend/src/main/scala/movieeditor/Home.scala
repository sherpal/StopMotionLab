package movieeditor

import com.raquo.laminar.api.L.*
import be.doeraene.webcomponents.ui5.*
import be.doeraene.webcomponents.ui5.configkeys.IconName
import components.{MovieList, Router, base}
import data.movie.{Movie, MovieMetadata}
import services.{ImagesService, MoviesService}

import scala.concurrent.ExecutionContext

object Home {

  def apply()(using imagesService: ImagesService, moviesService: MoviesService)(using ExecutionContext): HtmlElement = {

    val moviesVar = Var(Vector.empty[MovieMetadata])

    val createMovieBus = new EventBus[Unit]
    val deleteMovieBus = new EventBus[Movie.Id]

    div(
      Title.h1("Movie Editor"),
      Button.of(
        _.iconOnly := true,
        _.icon     := IconName.add,
        _.events.onClick.preventDefault.mapToUnit --> createMovieBus.writer
      ),
      MovieList(moviesVar.signal, deleteMovieBus.writer),
      EventStream.fromFuture(moviesService.movies) --> moviesVar.writer,
      createMovieBus.events
        .flatMapSwitch(_ => EventStream.fromFuture(moviesService.create()))
        .map(id => (base / entry.DefinedRoutes.movieEditorPath).createPath(id)) --> Observer[String](path =>
        Router.router.moveTo("/" ++ path)
      ),
      deleteMovieBus.events
        .flatMapSwitch(movieId => EventStream.fromFuture(moviesService.deleteWithRetries(movieId)))
        .flatMapSwitch(_ => EventStream.fromFuture(moviesService.movies)) --> moviesVar.writer
    )

  }

}
