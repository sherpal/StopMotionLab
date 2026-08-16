package communication.webrtc

import io.circe.Codec

case class Answer(sdp: String) derives Codec
