package facades

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import org.scalajs.dom

import scala.scalajs.js.UndefOr
import scala.scalajs.js.JSConverters.*

@js.native
trait VideoEncoderConfig extends js.Object {
  var codec: String                   = js.native
  var width: Int                      = js.native
  var height: Int                     = js.native
  var bitrate: js.UndefOr[Double]     = js.native
  var framerate: js.UndefOr[Double]   = js.native
  var latencyMode: js.UndefOr[String] = js.native
}

trait VideoEncoderEncodeOptions extends js.Object {
  val keyFrame: js.UndefOr[Boolean]
}
object VideoEncoderEncodeOptions {
  def apply(): VideoEncoderEncodeOptions = new VideoEncoderEncodeOptions {
    override val keyFrame: UndefOr[Boolean] = js.undefined
  }

  def apply(keyFrame: Boolean): VideoEncoderEncodeOptions = {
    val keyFrame0 = keyFrame
    new VideoEncoderEncodeOptions {
      override val keyFrame: UndefOr[Boolean] = keyFrame0
    }
  }
}

@js.native
trait EncodedVideoChunk extends js.Object {
  val `type`: String               = js.native
  val timestamp: Double            = js.native
  val duration: js.UndefOr[Double] = js.native
  val byteLength: Int              = js.native

  def copyTo(destination: js.Any): Unit = js.native // todo: find proper type
}

@js.native
trait VideoEncoderOutputMetadata extends js.Object {
  // We'll need decoderConfig later for muxing.
}

trait VideoEncoderInit extends js.Object {
  val output: js.Function2[
    EncodedVideoChunk,
    VideoEncoderOutputMetadata,
    Unit
  ]

  val error: js.Function1[dom.DOMException, Unit]
}

@js.native
@JSGlobal
class VideoEncoder(
    init: VideoEncoderInit
) extends js.Object {

  def configure(config: VideoEncoderConfig): Unit = js.native

  def encode(
      frame: VideoFrame,
      options: js.UndefOr[VideoEncoderEncodeOptions] = js.undefined
  ): Unit = js.native

  def flush(): js.Promise[Unit] = js.native

  def reset(): Unit = js.native

  def close(): Unit = js.native

  val encodeQueueSize: Int = js.native
  val state: String        = js.native
}

trait VideoFrameInit extends js.Object {
  val timestamp: Double
  val duration: js.UndefOr[Double]
}

object VideoFrameInit {
  def apply(timestamp: Double, duration: Option[Double] = None): VideoFrameInit = {
    val timestamp0 = timestamp
    val duration0  = duration
    new VideoFrameInit {
      override val timestamp: Double         = timestamp0
      override val duration: UndefOr[Double] = duration0.orUndefined
    }
  }
}

@js.native
@JSGlobal
class VideoFrame(
    source: js.Any,
    init: VideoFrameInit
) extends js.Object {

  val codedWidth: Int              = js.native
  val codedHeight: Int             = js.native
  val timestamp: Double            = js.native
  val duration: js.UndefOr[Double] = js.native

  def close(): Unit = js.native
}
