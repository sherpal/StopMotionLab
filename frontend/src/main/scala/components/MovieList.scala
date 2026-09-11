package components

import be.doeraene.webcomponents.ui5.configkeys.{ButtonDesign, IconName, ListItemType, ListSeparator}
import be.doeraene.webcomponents.ui5.{Button, Dialog, ListItem, UList}
import com.raquo.laminar.api.L.*
import data.movie.{Movie, MovieMetadata}

import scala.scalajs.js

object MovieList {

  def apply(
      movies: Signal[Vector[MovieMetadata]],
      loading: Signal[Boolean],
      deleteMovieObserver: Observer[Movie.Id]
  ): HtmlElement = {

    // The movie about to be deleted, kept around just long enough to ask "are you sure?" — a whole movie is a lot
    // more to lose than a couple of frames, so unlike the frame-delete flow this one always confirms.
    val pendingDeleteVar = Var(Option.empty[MovieMetadata])
    val closeDialogBus   = new EventBus[Unit]
    val confirmDeleteBus = new EventBus[Unit]

    div(
      UList.of(
        _.separators := ListSeparator.All,
        _.noDataText := "Aucun film pour l'instant — crée-en un !",
        _.loading <-- loading,
        _ =>
          children <-- movies
            .split(_.id)((key, _, updates) => movieRow(key, updates, pendingDeleteVar.writer.contramap(Some(_))))
      ),
      Dialog.of(
        _.showFromEvents(pendingDeleteVar.signal.changes.collect { case Some(_) => () }),
        _.closeFromEvents(closeDialogBus.events),
        _.headerText := "Supprimer ce film",
        _ =>
          sectionTag(
            p(
              child.text <-- pendingDeleteVar.signal.map(
                _.fold("")(movie =>
                  s"""Tu vas supprimer « ${movie.name} » définitivement. Cette action est irréversible."""
                )
              )
            )
          ),
        _.slots.footer := div(
          display.flex,
          alignItems.end,
          gap.px := 8,
          Button.of(
            _.design := ButtonDesign.Negative,
            _ => "Supprimer",
            _.events.onClick.mapToUnit --> confirmDeleteBus.writer
          ),
          Button.of(
            _.design := ButtonDesign.Transparent,
            _ => "Annuler",
            _.events.onClick.mapToUnit --> closeDialogBus.writer
          )
        )
      ),
      confirmDeleteBus.events.sample(pendingDeleteVar.signal).collect { case Some(movie) =>
        movie.id
      } --> deleteMovieObserver,
      confirmDeleteBus.events.mapToUnit --> closeDialogBus.writer,
      closeDialogBus.events.mapTo(None) --> pendingDeleteVar.writer
    )
  }

  private def formatLastUpdate(millis: Long): String = {
    val date = new js.Date(millis.toDouble * 1000)
    s"Modifié le ${date.toLocaleDateString()} à ${date.toLocaleTimeString()}"
  }

  private def movieRow(
      movieId: Movie.Id,
      updates: Signal[MovieMetadata],
      askDeleteObserver: Observer[MovieMetadata]
  ): HtmlElement = {
    val deleteClickBus = new EventBus[Unit]

    ListItem.of(
      _.icon      := IconName.video,
      _.tpe       := ListItemType.Navigation,
      _.navigated := true,
      _.description <-- updates.map(movie => formatLastUpdate(movie.lastUpdateAt)),
      _ => child.text <-- updates.map(_.name),
      // ui5-li doesn't expose its own click event helper, but it's a normal element under the hood, so the plain
      // Laminar onClick works directly.
      _ =>
        onClick.preventDefault.mapToUnit --> Observer[Unit](_ =>
          Router.router.moveTo("/" ++ (base / entry.DefinedRoutes.movieEditorPath).createPath(movieId))
        ),
      _.slots.deleteButton := Button.of(
        _.iconOnly := true,
        _.icon     := IconName.delete,
        _.design   := ButtonDesign.Transparent,
        _.tooltip  := "Supprimer ce film",
        // stopPropagation, otherwise this click would also bubble up as a row click and open the movie right
        // before asking whether to delete it.
        _.events.onClick.stopPropagation.preventDefault.mapToUnit --> deleteClickBus.writer
      ),
      _ => deleteClickBus.events.sample(updates) --> askDeleteObserver
    )
  }

}
