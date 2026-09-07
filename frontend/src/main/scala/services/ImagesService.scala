package services

import communication.http.HttpClient
import data.images.ImageData
import data.movie.Movie
import urldsl.language.PathSegment
import urldsl.language.dummyErrorImpl.*
import org.scalajs.dom
import org.scalajs.dom.{HttpMethod, RequestInit, Response}
import urldsl.errors.DummyError
import urldsl.vocabulary.{FromString, Printer}

import scala.scalajs.js
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ImagesService(maybeHost: Option[String])(using httpClient: HttpClient)(using ExecutionContext) {

  private val pathPrefix = maybeHost.fold("/")(_.stripSuffix("/") ++ "/")
  private val api        = root / HttpClient.apiPrefix

  def imageUrl(data: ImageData): String =
    pathPrefix ++ (api / "images" / segment[ImageData.Id]).createPath(data.id)

  private val postImagePath =
    (api / "images" / "upload") ? (param[Movie.Id]("movieId") & param[String]("recipient"))

  def postImage(movieId: Movie.Id, recipient: String, blob: dom.Blob): Future[Response] =
    dom
      .fetch(
        pathPrefix ++ postImagePath.createUrlString((), (movieId, recipient)),
        new RequestInit {
          method = HttpMethod.POST
          body = blob
          headers = js.Dictionary("Content-Type" -> blob.`type`)
        }
      )
      .toFuture

  def getImageBytes(imageId: ImageData.Id): Future[(ImageData.MimeType, Array[Byte])] =
    httpClient.get.bytes(api / "images" / segment[ImageData.Id])(imageId).map { (contentType, bytes) =>
      ImageData.MimeType.unsafeFromString(contentType) -> bytes
    }

  def getImageUrlEncoded(imageId: ImageData.Id): Future[String] =
    getImageBytes(imageId).map { case (mimeType, bytes) =>
      val base64 = java.util.Base64.getEncoder.encodeToString(bytes)
      s"data:${mimeType.value};base64,$base64"
    }

}
