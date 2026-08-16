package communication.webrtc

import io.circe.Codec

case class OnIceCandidateEvent(
    candidate: String,
    sdpMid: String,
    sdpMLineIndex: Double
) derives Codec
