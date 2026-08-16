package utils.videoencoding

import org.scalajs.dom

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import facades.*
import facades.mediabunny.*

import scala.scalajs.js.JavaScriptException
import scala.scalajs.js.typedarray.ArrayBuffer

private def loadImage(dataUrl: String): Future[dom.ImageBitmap] = {
  val promise = Promise[dom.ImageBitmap]()
  val image   = dom.document
    .createElement("img")
    .asInstanceOf[dom.html.Image]

  image.onload = { _ =>
    dom.window
      .createImageBitmap(image)
      .`then`(
        bitmap => promise.success(bitmap),
        error => promise.failure(JavaScriptException(error))
      )
  }

  image.src = dataUrl

  promise.future
}

def encodeToVideo(
    images: Iterable[String],
    imagesPerSecond: Int
)(using ExecutionContext): Future[ArrayBuffer] = {
  val target =
    new BufferTarget()

  val source =
    new VideoSampleSource(
      js.Dynamic.literal(
        codec = "vp9",
        bitrate = 5_000_000
      )
    )

  val output =
    new Output(
      js.Dynamic.literal(
        format = WebMOutputFormat(),
        target = target
      )
    )

  output.addVideoTrack(source)

  def addFrames(remaining: List[(dataUrl: String, index: Int)]): Future[Unit] = remaining match {
    case Nil          => Future.successful(())
    case head :: rest =>
      val timestampMicros =
        head.index.toDouble * 1_000_000.0 / imagesPerSecond

      (for {
        imageBitmap <- loadImage(head.dataUrl)
        frame = VideoFrame(
          imageBitmap,
          VideoFrameInit(
            timestamp = timestampMicros
          )
        )
        sample = VideoSample(frame)
        _ <- source.add(sample).toFuture
      } yield sample.close()).flatMap(_ => addFrames(rest))
  }

  for
    _ <- output.start().toFuture
    _ <- addFrames(images.toList.zipWithIndex)
    _ <- output.finalizeOutput().toFuture
  yield target.buffer.toOption.get
  // buffer is the finished Movie file
}
