package be.doeraene.services.videoencoding

import java.awt.geom.AffineTransform

/** The EXIF orientation tag (0x0112) of a JPEG, i.e. how the raw pixels must be transformed to be displayed upright.
  *
  * Phones (e.g. via `ImageCapture.takePhoto`) store the pixels as the sensor saw them and only record the rotation in
  * this tag. Browsers honour it when displaying images, but `ImageIO` ignores it completely, so we have to apply it
  * ourselves to get the same frames as in the editor.
  */
enum ExifOrientation(val value: Int) {
  case Normal         extends ExifOrientation(1)
  case FlipHorizontal extends ExifOrientation(2)
  case Rotate180      extends ExifOrientation(3)
  case FlipVertical   extends ExifOrientation(4)
  case Transpose      extends ExifOrientation(5)
  case Rotate90CW     extends ExifOrientation(6)
  case Transverse     extends ExifOrientation(7)
  case Rotate90CCW    extends ExifOrientation(8)

  /** Whether width and height are swapped once the orientation is applied. */
  def swapsDimensions: Boolean = value >= 5

  def displayedSize(rawWidth: Int, rawHeight: Int): (width: Int, height: Int) =
    if swapsDimensions then (rawHeight, rawWidth) else (rawWidth, rawHeight)

  /** Maps raw pixel coordinates (in an image of the given raw dimensions) to displayed (upright) coordinates. */
  def transform(rawWidth: Double, rawHeight: Double): AffineTransform = {
    val (w, h) = (rawWidth, rawHeight)
    // AffineTransform(m00, m10, m01, m11, m02, m12): x' = m00 x + m01 y + m02, y' = m10 x + m11 y + m12
    this match {
      case Normal         => AffineTransform()
      case FlipHorizontal => AffineTransform(-1, 0, 0, 1, w, 0)
      case Rotate180      => AffineTransform(-1, 0, 0, -1, w, h)
      case FlipVertical   => AffineTransform(1, 0, 0, -1, 0, h)
      case Transpose      => AffineTransform(0, 1, 1, 0, 0, 0)
      case Rotate90CW     => AffineTransform(0, 1, -1, 0, h, 0)
      case Transverse     => AffineTransform(0, -1, -1, 0, h, w)
      case Rotate90CCW    => AffineTransform(0, -1, 1, 0, 0, w)
    }
  }
}

object ExifOrientation {

  def fromValue(value: Int): ExifOrientation = values.find(_.value == value).getOrElse(Normal)

  /** Reads the orientation from the APP1/Exif segment of the given JPEG bytes. Anything unexpected (not a JPEG, no
    * Exif segment, truncated data...) is treated as [[Normal]]: worst case the frame is shown sideways, which beats
    * failing the whole video.
    */
  def fromJpeg(bytes: Array[Byte]): ExifOrientation =
    try findOrientation(bytes).fold(Normal)(fromValue)
    catch { case _: IndexOutOfBoundsException => Normal }

  private def u8(bytes: Array[Byte], at: Int): Int = bytes(at) & 0xff

  private def findOrientation(bytes: Array[Byte]): Option[Int] = {
    if bytes.length < 4 || u8(bytes, 0) != 0xff || u8(bytes, 1) != 0xd8 then return None

    var position = 2
    while position + 4 <= bytes.length do {
      if u8(bytes, position) != 0xff then return None
      val marker        = u8(bytes, position + 1)
      val segmentLength = (u8(bytes, position + 2) << 8) | u8(bytes, position + 3)
      // start of scan: the metadata segments are all behind us
      if marker == 0xda then return None
      if marker == 0xe1 then {
        val segmentStart = position + 4
        val isExif       =
          new String(bytes, segmentStart, 4, java.nio.charset.StandardCharsets.US_ASCII) == "Exif" &&
            bytes(segmentStart + 4) == 0 && bytes(segmentStart + 5) == 0
        if isExif then return orientationInTiff(bytes, segmentStart + 6)
      }
      position += 2 + segmentLength
    }
    None
  }

  private def orientationInTiff(bytes: Array[Byte], tiffStart: Int): Option[Int] = {
    val littleEndian = u8(bytes, tiffStart) == 'I'.toInt

    def u16(at: Int): Int =
      if littleEndian then u8(bytes, at) | (u8(bytes, at + 1) << 8)
      else (u8(bytes, at) << 8) | u8(bytes, at + 1)

    def u32(at: Int): Int =
      if littleEndian then u16(at) | (u16(at + 2) << 16)
      else (u16(at) << 16) | u16(at + 2)

    val firstIfd   = tiffStart + u32(tiffStart + 4)
    val entryCount = u16(firstIfd)
    (0 until entryCount).iterator
      .map(index => firstIfd + 2 + 12 * index)
      .find(entry => u16(entry) == 0x0112)
      .map(entry => u16(entry + 8))
  }

}
