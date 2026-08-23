package services

import data.images.ImageData
import data.movie.Movie
import urldsl.language.PathSegment
import urldsl.language.dummyErrorImpl.*
import org.scalajs.dom
import org.scalajs.dom.{HttpMethod, RequestInit, Response}

import scala.scalajs.js
import scala.concurrent.{ExecutionContext, Future}

class ImagesService(maybeHost: Option[String])(using ExecutionContext) {

  private val pathPrefix = maybeHost.fold("/")(_.stripSuffix("/") ++ "/")
  private val api        = root / "api"

  def imageUrl(data: ImageData): String =
    pathPrefix ++ (api / "images" / segment[ImageData.Id]).createPath(data.id)

  private val postImagePath =
    (api / "images" / "upload") ? param[Movie.Id]("movieId")

  def postImage(movieId: Movie.Id, blob: dom.Blob): Future[Response] =
    dom
      .fetch(
        pathPrefix ++ postImagePath.createUrlString((), movieId),
        new RequestInit {
          method = HttpMethod.POST
          body = blob
          headers = js.Dictionary("Content-Type" -> blob.`type`)
        }
      )
      .toFuture

}
