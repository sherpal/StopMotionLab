package entry

import com.raquo.laminar.api.L.*
import components.{Route, Router, Routes, base, baseStr}
import movieeditor.{ComputerApp, Home}
import org.scalajs.dom
import phone.PhoneApp
import services.{HttpClient, ImagesService, MoviesService}
import utils.websocket.CommandBridgeClient

import scala.concurrent.ExecutionContext.Implicits.global
import urldsl.language.dummyErrorImpl.*

@main def run(): Unit = {
  import DefinedRoutes.*

  def isMobile = {
    val isCoarse = dom.window.matchMedia("(pointer: coarse)").matches
    val isSmall  = dom.window.matchMedia("(max-width: 768px)").matches

    isCoarse && isSmall
  }

  println(isMobile)

  println(baseStr)
  println(base.createPath())

  given castor.Context      = castor.Context.Simple.global
  given HttpClient          = HttpClient(None)
  given ImagesService       = ImagesService(None) // todo: the None will depend on where we are...
  given CommandBridgeClient = CommandBridgeClient()(using unsafeWindowOwner)
  given MoviesService       = MoviesService()

  summon[CommandBridgeClient].open()

  render(
    dom.document.getElementById("root"),
    div(
      h1("hello world"),

      child <-- Routes
        .firstOf(
          Route(base / home, _ => Home()),
          Route(base / phonePath, _ => PhoneApp()),
          Route(base / movieEditorPath, ComputerApp(_)),
          Route(
            base,
            _ =>
              div(
                onMountCallback { _ =>
                  Router.router.moveTo(
                    "/" ++ (base / (if isMobile then phonePath else home)).createPath()
                  )
                }
              )
          )
        )
        .map(_.getOrElse(div("oopsy")))
    )
  )

}
