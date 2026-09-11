package movieeditor

import be.doeraene.webcomponents.ui5.Card
import com.raquo.laminar.api.L.*
import data.images.ImageData
import data.movie.Movie
import services.{ImagesService, MoviesService}

import scala.concurrent.ExecutionContext

object MovieDisplay {

  /** The goal of this component is to display the work in progress movie: a selection toolbar, the filmstrip of
    * thumbnails to scrub through it, and a large preview of the frame currently scrolled to.
    */
  def apply(movieId: Movie.Id, imagesSignal: Signal[Vector[ImageData]])(using
      movieService: MoviesService
  )(using
      ImagesService,
      ExecutionContext
  ): HtmlElement = {
    val selectedIndices   = Var(Set.empty[Int])
    val scrollPositionVar = Var(0)

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

          StoryboardSelectionToolbar(movieId, selectedIndices, imagesSignal),
          StoryboardImageStrip(imagesSignal, selectedIndices, scrollPositionVar),
          StoryboardBigImageDisplay(scrollPositionVar.signal, imagesSignal)
        )
    )

  }

}
