package entry

import com.raquo.laminar.api.L.*
import components.{Route, Router, Routes, base, baseStr}
import computer.ComputerApp
import org.scalajs.dom
import org.scalajs.dom.MediaStreamConstraints
import phone.PhoneApp

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success}
import urldsl.language.dummyErrorImpl.*

val phonePath    = root / "phone"
val computerPath = root / "computer"

@main def run(): Unit = {

  def isMobile = {
    val isCoarse = dom.window.matchMedia("(pointer: coarse)").matches
    val isSmall  = dom.window.matchMedia("(max-width: 768px)").matches

    isCoarse && isSmall
  }

  println(isMobile)

  println(baseStr)
  println(base.createPath())

  render(
    dom.document.getElementById("root"),
    div(
      h1("hello world"),

      child <-- Routes
        .firstOf(
          Route(base / phonePath, _ => PhoneApp()),
          Route(base / computerPath, _ => ComputerApp()),
          Route(
            base,
            _ =>
              div(
                onMountCallback { _ =>
                  Router.router.moveTo(
                    "/" ++ (base / (if isMobile then phonePath else computerPath)).createPath()
                  )
                }
              )
          )
        )
        .map(_.getOrElse(div("oopsy")))
    )
  )

}
