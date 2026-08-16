package utils

import org.scalajs.dom
import org.scalajs.dom.CanvasRenderingContext2D

import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.JavaScriptException
import scala.util.Success

def sleep(ms: Int): Future[Unit] = {
  val p = Promise[Unit]()
  js.timers.setTimeout(1000) {
    p.complete(Success(()))
  }
  p.future
}

def loadImageDataUrl(path: String): Future[String] = {
  val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
  val ctx    = canvas.getContext("2d").asInstanceOf[CanvasRenderingContext2D]

  val promise = Promise[String]()

  val img = dom.Image()
  img.onload = { _ =>
    canvas.width = img.width
    canvas.height = img.height
    ctx.drawImage(img, 0, 0)
    promise.success(canvas.toDataURL("image/png"))
  }
  img.addEventListener("error", event => promise.failure(JavaScriptException(event)))
  img.src = path

  promise.future
}

def createTestImage(index: Int, count: Int, size: Int = 500): String = {
  val canvas = dom.document
    .createElement("canvas")
    .asInstanceOf[dom.HTMLCanvasElement]

  canvas.width = size
  canvas.height = size

  val ctx = canvas
    .getContext("2d")
    .asInstanceOf[CanvasRenderingContext2D]

  // Black background
  ctx.fillStyle = "black"
  ctx.fillRect(0, 0, size, size)

  // Position of the white square along the diagonal
  val squareSize  = size / 5.0
  val maxPosition = size - squareSize
  val position    =
    if count <= 1 then 0
    else maxPosition * index / (count - 1)

  // White square
  ctx.fillStyle = "white"
  ctx.fillRect(
    position,
    position,
    squareSize,
    squareSize
  )

  canvas.toDataURL("image/png")
}
