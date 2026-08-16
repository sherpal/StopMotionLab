package facades.mediabunny

import facades.{EncodedVideoChunk, VideoEncoderOutputMetadata, VideoFrame}

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSImport, JSName}

@js.native
@JSImport("mediabunny", "EncodedPacket")
class EncodedPacket extends js.Object

@js.native
@JSImport("mediabunny", "EncodedPacket")
object EncodedPacket extends js.Object {
  def fromEncodedChunk(
      chunk: EncodedVideoChunk
  ): EncodedPacket = js.native
}

@js.native
@JSImport("mediabunny", "EncodedVideoPacketSource")
class EncodedVideoPacketSource(
    codec: String
) extends js.Object {

  def add(
      packet: EncodedPacket,
      meta: js.UndefOr[VideoEncoderOutputMetadata] = js.undefined
  ): js.Promise[Unit] = js.native

  def close(): Unit = js.native
}

@js.native
@JSImport("mediabunny", "WebMOutputFormat")
class WebMOutputFormat extends js.Object

@js.native @JSImport("mediabunny", "Mp4OutputFormat")
class Mp4OutputFormat extends js.Object

@js.native
@JSImport("mediabunny", "BufferTarget")
class BufferTarget extends js.Object {
  val buffer: js.UndefOr[js.typedarray.ArrayBuffer] = js.native
}

@js.native
@JSImport("mediabunny", "Output")
class Output(
    options: js.Object
) extends js.Object {

  def addVideoTrack(source: EncodedVideoPacketSource | VideoSampleSource): js.Object = js.native

  def start(): js.Promise[Unit] = js.native

  @JSName("finalize")
  def finalizeOutput(): js.Promise[Unit] = js.native
}

@js.native
@JSImport("mediabunny", "VideoSample")
class VideoSample(
    data: VideoFrame,
    init: js.UndefOr[js.Object] = js.undefined
) extends js.Object {

  def close(): Unit = js.native
}

@js.native
@JSImport("mediabunny", "VideoSampleSource")
class VideoSampleSource(
    encodingConfig: js.Object
) extends js.Object {

  def add(
      sample: VideoSample,
      encodeOptions: js.UndefOr[js.Object] = js.undefined
  ): js.Promise[Unit] = js.native

  def close(): Unit = js.native
}
