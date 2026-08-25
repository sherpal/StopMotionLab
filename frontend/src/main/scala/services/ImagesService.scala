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

import java.util.UUID
import scala.scalajs.js
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ImagesService(maybeHost: Option[String])(using ExecutionContext) {

  private val pathPrefix = maybeHost.fold("/")(_.stripSuffix("/") ++ "/")
  private val api        = root / HttpClient.apiPrefix

  def imageUrl(data: ImageData): String =
    pathPrefix ++ (api / "images" / segment[ImageData.Id]).createPath(data.id)

  private val postImagePath =
    (api / "images" / "upload") ? (param[Movie.Id]("movieId") & param[java.util.UUID]("recipient"))

  def postImage(movieId: Movie.Id, recipient: java.util.UUID, blob: dom.Blob): Future[Response] =
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

  given FromString[UUID, DummyError] = str => Try(UUID.fromString(str)).toEither.left.map(_ => DummyError.dummyError)
  given Printer[UUID]                = _.toString

}
