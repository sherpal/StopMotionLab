package data.video

import io.circe.{Codec, Decoder, Encoder}
import urldsl.errors.DummyError
import urldsl.vocabulary.{FromString, Printer}

import java.util.UUID
import scala.util.Try

/** A movie being encoded into a video by the server. Encoding long movies takes minutes, way longer than browsers are
  * willing to wait for an HTTP response, so the client starts a job, polls its [[VideoEncodingJob.Status]], and
  * downloads the video once it's [[VideoEncodingJob.Status.Done]].
  */
object VideoEncodingJob {

  opaque type Id = UUID

  object Id {
    given Codec[Id] = Codec.from(Decoder.decodeUUID, Encoder.encodeUUID)

    def random(): Id = UUID.randomUUID()

    inline def fromUUID(id: UUID): Id = id

    extension (id: Id) {
      inline def uuidValue: UUID = id
    }
  }

  given FromString[Id, DummyError] =
    FromString.factory(str => Try(UUID.fromString(str)).toEither.left.map(_ => DummyError.dummyError))
  given Printer[Id] = Printer.factory(_.toString)

  enum Status derives Codec {

    /** Waiting for the jobs before it to be done (only one video is encoded at a time); 1 means next in line. */
    case Queued(position: Int)
    case Running(encodedFrames: Int, totalFrames: Int)
    case Done
    case Failed(message: String)

    def isFinished: Boolean = this match {
      case Done | Failed(_) => true
      case _                => false
    }
  }

}
