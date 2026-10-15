package be.doeraene.services.videoencoding

import org.bytedeco.javacv.{FFmpegFrameGrabber, Java2DFrameConverter}

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.{ByteBuffer, ByteOrder}
import javax.imageio.ImageIO
import scala.util.Using

class VideoEncodingServiceTest extends munit.FunSuite {
  import VideoEncodingServiceTest.*

  private def assertColorClose(actual: Int, expected: Color, clue: String)(using munit.Location): Unit = {
    val actualColor = Color(actual)
    val distance    = Seq(
      actualColor.getRed - expected.getRed,
      actualColor.getGreen - expected.getGreen,
      actualColor.getBlue - expected.getBlue
    ).map(math.abs).max
    assert(distance < 40, s"$clue: expected $expected but got $actualColor")
  }

  test("Reads the EXIF orientation, in both byte orders") {
    val plain = jpeg(16, 8)((_, _) => Color.RED)
    assertEquals(ExifOrientation.fromJpeg(plain), ExifOrientation.Normal)
    assertEquals(
      ExifOrientation.fromJpeg(withOrientation(plain, 6, ByteOrder.LITTLE_ENDIAN)),
      ExifOrientation.Rotate90CW
    )
    assertEquals(
      ExifOrientation.fromJpeg(withOrientation(plain, 8, ByteOrder.BIG_ENDIAN)),
      ExifOrientation.Rotate90CCW
    )
    assertEquals(ExifOrientation.fromJpeg(Array[Byte](1, 2, 3)), ExifOrientation.Normal)
  }

  test("Encodes pictures into an mp4 video, one frame per picture, upright and fitted in the first one's size") {
    val output = os.temp(suffix = ".mp4")
    try {
      val red = jpeg(320, 240)((_, _) => Color.RED)
      // raw left half green and right half blue, to be rotated a quarter turn clockwise: green on top, blue below
      val rotated = withOrientation(
        jpeg(320, 240)((x, _) => if x < 160 then Color.GREEN else Color.BLUE),
        6,
        ByteOrder.LITTLE_ENDIAN
      )

      VideoEncodingService.encode(Iterator(red, rotated, red), imagesPerSecond = 5, output)

      val (frameRate, frames) = decodeFrames(output)
      assertEqualsDouble(frameRate, 5.0, 0.01)
      assertEquals(frames.length, 3)
      assertEquals(frames.map(frame => (frame.getWidth, frame.getHeight)).distinct, Vector((320, 240)))

      assertColorClose(frames(0).getRGB(160, 120), Color.RED, "first frame")
      assertColorClose(frames(1).getRGB(160, 60), Color.GREEN, "top of the rotated frame")
      assertColorClose(frames(1).getRGB(160, 180), Color.BLUE, "bottom of the rotated frame")
      // the portrait picture is 180 pixels wide once fitted in the 320x240 frame: black bars on each side
      assertColorClose(frames(1).getRGB(20, 120), Color.BLACK, "left bar of the rotated frame")
      assertColorClose(frames(1).getRGB(300, 120), Color.BLACK, "right bar of the rotated frame")
      assertColorClose(frames(2).getRGB(160, 120), Color.RED, "last frame")
    } finally os.remove(output)
  }

  test("Scales big pictures down to the maximum dimension, keeping even dimensions") {
    val output = os.temp(suffix = ".mp4")
    try {
      VideoEncodingService.encode(Iterator(jpeg(1001, 601)((_, _) => Color.WHITE)), 1, output, maxDimension = 500)
      val frame = decodeFrames(output).frames.head
      assertEquals((frame.getWidth, frame.getHeight), (500, 300))
    } finally os.remove(output)
  }

}

object VideoEncodingServiceTest {

  def jpeg(width: Int, height: Int)(paint: (Int, Int) => Color): Array[Byte] = {
    val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    for x <- 0 until width; y <- 0 until height do image.setRGB(x, y, paint(x, y).getRGB)
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "jpeg", out)
    out.toByteArray
  }

  /** Inserts, right after the SOI marker, an APP1/Exif segment containing only the orientation tag. */
  def withOrientation(jpegBytes: Array[Byte], orientation: Int, byteOrder: ByteOrder): Array[Byte] = {
    val tiff = ByteBuffer.allocate(8 + 2 + 12 + 4).order(byteOrder)
    tiff.put((if byteOrder == ByteOrder.LITTLE_ENDIAN then "II" else "MM").getBytes)
    tiff.putShort(42.toShort).putInt(8) // magic number, then offset of the first IFD
    tiff.putShort(1.toShort)            // number of entries in the IFD
    tiff.putShort(0x0112.toShort).putShort(3.toShort).putInt(1).putShort(orientation.toShort).putShort(0.toShort)
    tiff.putInt(0) // no next IFD

    val payload = "Exif".getBytes ++ Array[Byte](0, 0) ++ tiff.array()
    val length  = payload.length + 2
    val segment = Array[Byte](0xff.toByte, 0xe1.toByte, (length >> 8).toByte, length.toByte) ++ payload
    jpegBytes.take(2) ++ segment ++ jpegBytes.drop(2)
  }

  def decodeFrames(video: os.Path): (frameRate: Double, frames: Vector[BufferedImage]) =
    Using.resource(FFmpegFrameGrabber(video.toIO)) { grabber =>
      grabber.start()
      val converter = Java2DFrameConverter()
      val frames    = Iterator
        .continually(grabber.grabImage())
        .takeWhile(_ != null)
        .map(frame => Java2DFrameConverter.cloneBufferedImage(converter.convert(frame)))
        .toVector
      (grabber.getFrameRate, frames)
    }

}
