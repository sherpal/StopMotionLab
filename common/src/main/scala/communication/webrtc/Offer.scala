package communication.webrtc

import io.circe.Codec

case class Offer(
    sdp: String
) derives Codec
