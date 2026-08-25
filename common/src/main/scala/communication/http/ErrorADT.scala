package communication.http

import io.circe.Codec

sealed trait ErrorADT derives Codec:
  def message: String

object ErrorADT {

  case class Unknown() extends ErrorADT:
    def message: String = "Unknown error"

  class AsException(val err: ErrorADT) extends RuntimeException(err.message)

}
