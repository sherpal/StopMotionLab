package be.doeraene.qrcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

import scala.jdk.CollectionConverters.*

object QrCode {

  /** Renders `text` as a QR code, as a scalable SVG string.
    *
    * Rendered as raw `<rect>`s from the ZXing bit matrix rather than going through the `javase` module, so we avoid
    * pulling in AWT/raster image dependencies just to draw squares.
    */
  def svg(text: String, size: Int = 280): String = {
    val hints = Map[EncodeHintType, Any](
      EncodeHintType.MARGIN           -> Integer.valueOf(1),
      EncodeHintType.ERROR_CORRECTION -> ErrorCorrectionLevel.M
    ).asJava

    val matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val width  = matrix.getWidth
    val height = matrix.getHeight

    val rects = (for {
      y <- 0 until height
      x <- 0 until width
      if matrix.get(x, y)
    } yield s"""<rect x="$x" y="$y" width="1" height="1"/>""").mkString

    s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width $height" shape-rendering="crispEdges" fill="#000">$rects</svg>"""
  }

}
