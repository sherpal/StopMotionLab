package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{ButtonDesign, IconName, ValueState}
import be.doeraene.webcomponents.ui5.{Button, Dialog, Input}
import com.raquo.laminar.api.L.*
import components.{Router, base}
import data.movie.Movie
import services.MoviesService

import scala.concurrent.ExecutionContext

/** The icon button + confirmation dialog that permanently deletes the current movie. The delete button only
  * arms once the user has typed the movie's exact name, and a successful deletion navigates back home.
  */
object DeleteMovieDialog {

  // noinspection MutatorLikeMethodIsParameterless
  def apply(movieId: Movie.Id, movieNameSignal: Signal[String])(using
      movieService: MoviesService
  )(using ExecutionContext): HtmlElement = {
    val deleteClickBus = new EventBus[Unit]
    val closeDialogBus = new EventBus[Unit]
    val confirmBus     = new EventBus[Unit]
    val typedNameVar   = Var("")

    val matchesSignal: Signal[Boolean] =
      typedNameVar.signal.combineWithFn(movieNameSignal)(_ == _)

    div(
      Button.of(
        _.iconOnly := true,
        _.icon     := IconName.delete,
        _.design   := ButtonDesign.Negative,
        _.tooltip  := "Supprimer ce film",
        _.events.onClick.preventDefault.mapToUnit --> deleteClickBus.writer
      ),
      Dialog.of(
        _.showFromEvents(deleteClickBus.events.mapToUnit),
        _.closeFromEvents(closeDialogBus.events),
        _.headerText := "Supprimer ce film",
        // reset the typed text every time the dialog is (re)opened, so a leftover match from a previous,
        // cancelled attempt can't leave the button armed by accident.
        _ => deleteClickBus.events.mapTo("") --> typedNameVar.writer,
        _ =>
          sectionTag(
            display.flex,
            flexDirection.column,
            gap.px := 12,
            p(
              child.text <-- movieNameSignal.map(name =>
                s"""Pour confirmer, tape le nom du film ci-dessous : « $name »"""
              )
            ),
            Input.of(
              _.value <-- typedNameVar.signal,
              _.placeholder <-- movieNameSignal,
              _.valueState <-- matchesSignal.combineWithFn(typedNameVar.signal.map(_.isEmpty))((matches, empty) =>
                if empty then ValueState.None else if matches then ValueState.Positive else ValueState.Negative
              ),
              _.events.onInput.map(_.target.value) --> typedNameVar.writer
            ),
            p(
              small("You can recover a deleted movie from the home menu.")
            )
          ),
        _.slots.footer := div(
          display.flex,
          alignItems.end,
          gap.px := 8,
          Button.of(
            _.design := ButtonDesign.Negative,
            _.disabled <-- matchesSignal.invert,
            _ => "Supprimer définitivement",
            _.events.onClick.mapToUnit --> Observer.combine(closeDialogBus.writer, confirmBus.writer)
          ),
          Button.of(
            _.design := ButtonDesign.Transparent,
            _ => "Annuler",
            _.events.onClick.mapToUnit --> closeDialogBus.writer
          )
        )
      ),
      confirmBus.events
        .flatMapSwitch(_ => EventStream.fromFuture(movieService.deleteWithRetries(movieId)))
        .collect { case true => () } // delay a bit to let projection have a chance to run
        .delay(300) --> Observer[Unit](_ =>
        Router.router.moveTo("/" ++ (base / entry.DefinedRoutes.home).createPath())
      )
    )
  }

}
