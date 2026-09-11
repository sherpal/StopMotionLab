package movieeditor

import be.doeraene.webcomponents.ui5.configkeys.{IconName, TagDesign}
import be.doeraene.webcomponents.ui5.{Icon, Tag, Text}
import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import data.images.ImageData
import services.ImagesService

/** The large preview of the frame currently scrolled to in the storyboard, with a "current / total" frame
  * counter overlaid in the corner, or a placeholder while there are no frames yet.
  */
object StoryboardBigImageDisplay {

  def apply(scrollPositionSignal: Signal[Int], imagesSignal: Signal[Vector[ImageData]])(using
      imagesService: ImagesService
  ): HtmlElement = {
    val frameLabelSignal =
      imagesSignal
        .map(_.length)
        .combineWithFn(scrollPositionSignal)((count, index) =>
          if count == 0 then None else Some(s"${index.min(count - 1) + 1} / $count")
        )

    div(
      className := "smlab-framed",
      position.relative,
      width.percent := 100,
      minHeight.px  := 300,
      display.flex,
      alignItems.center,
      justifyContent.center,
      backgroundColor := "var(--sapList_Background, #eee)",

      child <-- imagesSignal.map { images =>
        if images.isEmpty then
          div(
            padding.px := 48,
            display.flex,
            flexDirection.column,
            alignItems.center,
            gap.px  := 8,
            opacity := 0.5,
            Icon.of(_.name := IconName.camera),
            Text("En attente d'images...")
          )
        else
          img(
            cls("smlab-fade-in"),
            height.px                               := 500,
            maxWidth.percent                        := 100,
            htmlAttr("object-fit", StringAsIsCodec) := "contain",
            src <-- scrollPositionSignal.map(index => images(index.min(images.length - 1))).map(imagesService.imageUrl)
          )
      },

      child.maybe <-- frameLabelSignal.map(
        _.map(label =>
          Tag.of(
            _.design := TagDesign.Neutral,
            _ => position.absolute,
            _ => top.px  := 8,
            _ => left.px := 8,
            _ => label
          )
        )
      )
    )
  }

}
