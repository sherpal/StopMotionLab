package components

import com.raquo.laminar.api.L.*
import be.doeraene.webcomponents.ui5.*
import be.doeraene.webcomponents.ui5.configkeys.IconName
import data.movie.{Movie, MovieMetadata}

object MovieList {

  def apply(movies: Signal[Vector[MovieMetadata]], deleteMovieObserver: Observer[Movie.Id]): HtmlElement = {
    UList(
      children <-- movies
        .split(_.id)((key, initialMovie, updates) => {
          UList.item.of(
            _ => child.text <-- updates.map(_.name),
            _ =>
              Button.of(
                _.iconOnly := true,
                _.icon     := IconName.`open-folder`,
                _.events.onClick.preventDefault.mapToUnit --> Observer[Unit](_ =>
                  Router.router.moveTo("/" ++ (base / entry.DefinedRoutes.movieEditorPath).createPath(key))
                )
              ),
            _ =>
              Button.of(
                _.iconOnly := true,
                _.icon     := IconName.delete,
                _.events.onClick.preventDefault.mapTo(key) --> deleteMovieObserver
              )
          )
        })
    )
  }

}
