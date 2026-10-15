package be.doeraene.services.videoencoding

import be.doeraene.services.images.ImagesService
import be.doeraene.services.movies.MoviesService
import data.images.ImageData
import data.movie.Movie
import org.bytedeco.ffmpeg.global.{avcodec, avutil}
import org.bytedeco.javacv.{FFmpegFrameRecorder, Frame}

import java.awt.image.{BufferedImage, DataBufferByte}
import java.awt.{Color, RenderingHints}
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import javax.imageio.{ImageIO, ImageReader}
import scala.util.Using

/** Turns a movie's pictures into an H.264 mp4 video, using FFmpeg (bundled with the app through JavaCV, so nothing to
  * install).
  *
  * This used to happen in the browser, which had to hold every (multi-megabyte) picture in memory at once and ran out
  * of it after ~150 pictures. Here, pictures are fetched, decoded and encoded one at a time, so memory usage doesn't
  * depend on the length of the movie, and the video itself is written straight to disk.
  *
  * FFmpeg was chosen (over pure-JVM encoders such as jcodec) because it can also encode and mix audio, which we'll need
  * to add sound effects to movies.
  *
  * @param maxDimension
  *   longest side, in pixels, of the produced video. Phone pictures are typically 12+ megapixels, way more than a video
  *   needs, and encoding time grows with the number of pixels.
  */
class VideoEncodingService(maxDimension: Int = VideoEncodingService.defaultMaxDimension)(using
    moviesService: MoviesService,
    imagesService: ImagesService
) {
  import VideoEncodingService.*

  /** The movie to encode, if it can be encoded with these settings. */
  def movieToEncode(movieId: Movie.Id, imagesPerSecond: Int): Either[EncodingError, Movie] =
    for {
      _     <- Either.cond(imagesPerSecond > 0, (), EncodingError.InvalidImagesPerSecond(imagesPerSecond))
      movie <- moviesService.movie(movieId).toRight(EncodingError.MovieNotFound(movieId))
      _     <- Either.cond(movie.sortedImages.nonEmpty, (), EncodingError.NoImages(movieId))
    } yield movie

  /** Encodes the given images, in order, into an mp4 file at `output`. Blocks until done, which can take minutes.
    *
    * @param onFrameEncoded
    *   called with the number of frames encoded so far, after each one
    */
  def encodeImages(
      images: Vector[ImageData],
      imagesPerSecond: Int,
      output: os.Path,
      onFrameEncoded: Int => Unit = _ => ()
  ): Unit =
    encode(images.iterator.map(loadImage), imagesPerSecond, output, maxDimension, onFrameEncoded)

  private def loadImage(image: ImageData): Array[Byte] =
    imagesService
      .retrieve(image.id)
      .getOrElse(throw new NoSuchElementException(s"Image ${image.id} of the movie does not exist (anymore)."))(1)

}

object VideoEncodingService {

  // FFmpeg dumps the full description of every file it writes (or reads) at info level, which is just noise here.
  avutil.av_log_set_level(avutil.AV_LOG_ERROR)

  val defaultMaxDimension: Int = 1920

  /** Encodes the given pictures (raw JPEG/PNG bytes), in order, into an mp4 file at `output`. The video's dimensions
    * are those of the first picture (scaled down to fit `maxDimension`); pictures with a different aspect ratio are
    * fitted inside, with black bars.
    */
  def encode(
      pictures: Iterator[Array[Byte]],
      imagesPerSecond: Int,
      output: os.Path,
      maxDimension: Int = defaultMaxDimension,
      onFrameEncoded: Int => Unit = _ => ()
  ): Unit = {
    // Allocated lazily, once we know the dimensions of the first picture, then reused for every frame.
    var maybeRecorder = Option.empty[(recorder: FFmpegFrameRecorder, canvas: BufferedImage)]

    try {
      pictures.zipWithIndex.foreach { (bytes, index) =>
        val (recorder, canvas) = maybeRecorder.getOrElse {
          val (width, height) = videoSize(bytes, maxDimension)
          val started         = (
            recorder = startRecorder(output, width, height, imagesPerSecond),
            canvas = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
          )
          maybeRecorder = Some(started)
          started
        }

        try drawFitted(bytes, canvas)
        catch {
          case error: Exception =>
            throw new IllegalArgumentException(s"Could not read picture #${index + 1} of the movie", error)
        }

        val pixels = canvas.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData
        recorder.recordImage(
          canvas.getWidth,
          canvas.getHeight,
          Frame.DEPTH_UBYTE,
          3,
          canvas.getWidth * 3,
          avutil.AV_PIX_FMT_BGR24,
          ByteBuffer.wrap(pixels)
        )
        onFrameEncoded(index + 1)
      }

      maybeRecorder.foreach(_.recorder.stop())
    } finally maybeRecorder.foreach(_.recorder.release())
  }

  enum EncodingError {
    case InvalidImagesPerSecond(imagesPerSecond: Int)
    case MovieNotFound(movieId: Movie.Id)
    case NoImages(movieId: Movie.Id)

    def message: String = this match {
      case InvalidImagesPerSecond(imagesPerSecond) =>
        s"The number of images per second must be positive (got $imagesPerSecond)."
      case MovieNotFound(movieId) => s"Movie ${movieId.value} does not exist."
      case NoImages(movieId)      => s"Movie ${movieId.value} does not have any image."
    }
  }

  private def startRecorder(output: os.Path, width: Int, height: Int, imagesPerSecond: Int): FFmpegFrameRecorder = {
    // no audio channel for now; sound effects will need one
    val recorder = FFmpegFrameRecorder(output.toIO, width, height, 0)
    recorder.setFormat("mp4")
    recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264)
    recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P)
    recorder.setFrameRate(imagesPerSecond.toDouble)
    // Every picture of a stop motion differs from the previous one, so there's a lot to encode for each frame.
    recorder.setVideoBitrate(8_000_000)
    // moves the index to the beginning of the file, so that players can start before having read everything
    recorder.setOption("movflags", "+faststart")
    recorder.start()
    recorder
  }

  /** Raw (on disk, before applying the EXIF orientation) dimensions of the picture, read from its header only. */
  private def rawSize(bytes: Array[Byte]): (width: Int, height: Int) =
    withReader(bytes)(reader => (reader.getWidth(0), reader.getHeight(0)))

  /** H.264 (in 4:2:0) needs even dimensions. */
  private def even(size: Double): Int = math.max(2, (math.round(size / 2.0) * 2).toInt)

  private def videoSize(firstPicture: Array[Byte], maxDimension: Int): (width: Int, height: Int) = {
    val raw             = rawSize(firstPicture)
    val (width, height) = ExifOrientation.fromJpeg(firstPicture).displayedSize(raw.width, raw.height)
    val scale           = math.min(1.0, maxDimension.toDouble / math.max(width, height))
    (even(width * scale), even(height * scale))
  }

  private def withReader[T](bytes: Array[Byte])(f: ImageReader => T): T =
    Using.resource(ImageIO.createImageInputStream(ByteArrayInputStream(bytes))) { input =>
      val readers = ImageIO.getImageReaders(input)
      if !readers.hasNext then throw new IllegalArgumentException("Unsupported image format")
      val reader = readers.next()
      try {
        reader.setInput(input, true, true)
        f(reader)
      } finally reader.dispose()
    }

  /** Decodes the picture and draws it, upright and centered, as large as possible inside `canvas`. */
  private def drawFitted(bytes: Array[Byte], canvas: BufferedImage): Unit = {
    val orientation = ExifOrientation.fromJpeg(bytes)

    val image = withReader(bytes) { reader =>
      val (rawWidth, rawHeight) = (reader.getWidth(0), reader.getHeight(0))
      val displayed             = orientation.displayedSize(rawWidth, rawHeight)
      val scale = math.min(canvas.getWidth.toDouble / displayed.width, canvas.getHeight.toDouble / displayed.height)
      // Decoding only every n-th pixel is much faster and lighter than decoding the full 12+ megapixels and scaling
      // down afterwards. Stay at or above the target size, so that the final (smooth) scaling only ever shrinks.
      val subsampling = math.max(1, (1.0 / scale).toInt)
      val param       = reader.getDefaultReadParam
      param.setSourceSubsampling(subsampling, subsampling, 0, 0)
      reader.read(0, param)
    }

    val displayed = orientation.displayedSize(image.getWidth, image.getHeight)
    val scale     = math.min(canvas.getWidth.toDouble / displayed.width, canvas.getHeight.toDouble / displayed.height)

    val graphics = canvas.createGraphics()
    try {
      graphics.setColor(Color.BLACK)
      graphics.fillRect(0, 0, canvas.getWidth, canvas.getHeight)
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      graphics.translate(
        (canvas.getWidth - displayed.width * scale) / 2,
        (canvas.getHeight - displayed.height * scale) / 2
      )
      graphics.scale(scale, scale)
      graphics.transform(orientation.transform(image.getWidth, image.getHeight))
      graphics.drawImage(image, 0, 0, null)
    } finally graphics.dispose()
  }

}
