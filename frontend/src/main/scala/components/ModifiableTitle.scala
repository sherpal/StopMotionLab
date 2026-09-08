package components

import com.raquo.laminar.api.L.*
import be.doeraene.webcomponents.ui5.*
import be.doeraene.webcomponents.ui5.configkeys.TitleLevel

object ModifiableTitle {

  def h1(textSignal: Signal[String], changeObserver: Observer[String]): HtmlElement =
    make(TitleLevel.H1, textSignal, changeObserver)

  def h2(textSignal: Signal[String], changeObserver: Observer[String]): HtmlElement =
    make(TitleLevel.H2, textSignal, changeObserver)

  def h3(textSignal: Signal[String], changeObserver: Observer[String]): HtmlElement =
    make(TitleLevel.H3, textSignal, changeObserver)

  def h4(textSignal: Signal[String], changeObserver: Observer[String]): HtmlElement =
    make(TitleLevel.H4, textSignal, changeObserver)

  def h5(textSignal: Signal[String], changeObserver: Observer[String]): HtmlElement =
    make(TitleLevel.H5, textSignal, changeObserver)

  def h6(textSignal: Signal[String], changeObserver: Observer[String]): HtmlElement =
    make(TitleLevel.H6, textSignal, changeObserver)

  private def make(level: TitleLevel, textSignal: Signal[String], changeObserver: Observer[String]): HtmlElement = {
    val isModifying = Var(false)

    Title.of(
      _.level := level,
      _ =>
        child.maybe <-- isModifying.signal.invert.map(Option.when(_) {
          span(
            cursor.pointer,
            child.text <-- textSignal,
            onClick.mapTo(true) --> isModifying.writer
          )
        }),
      _ =>
        child.maybe <-- isModifying.signal.map(Option.when(_) {
          Input.of(
            _.value <-- textSignal,
            _.events.onChange.mapToValue --> Observer.combine[String](
              isModifying.writer.contramap(_ => false),
              changeObserver
            )
          )
        })
    )

  }

}
