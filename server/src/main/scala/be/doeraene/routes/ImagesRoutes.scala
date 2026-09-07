package be.doeraene.routes

import be.doeraene.services.connectedclients.ConnectedClientsService
import be.doeraene.services.images.ImagesService
import data.images.ImageData
import data.movie.Movie
import eventsourcing.EventSourcingService
import be.doeraene.utils.HttpDates
import be.doeraene.utils.castorutils.ask

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

//noinspection TypeAnnotation
class ImagesRoutes(using
    imageService: ImagesService,
    connectedClientsService: ConnectedClientsService,
    eventSourcingService: EventSourcingService
)(using
    castor.Context,
    cask.util.Logger
) extends cask.Routes {

  @cask.get("api/images/:id")
  def imageBytes(id: String, request: cask.Request) = {
    val imageId = ImageData.Id.fromUUID(java.util.UUID.fromString(id))

    imageService.lastUpdated(imageId) match {
      case None => cask.Response(Array.empty[Byte], statusCode = 404)

      case Some(lastUpdateAt) =>
        val lastModified = HttpDates.format(lastUpdateAt)

        // "no-cache" doesn't mean "don't cache": it means the browser may keep the bytes, but must always
        // revalidate with the server first, which is what makes the check below (and its 304 short-circuit) useful.
        val cacheControl = "Cache-Control" -> "no-cache"

        val notModifiedSinceLastUpdate =
          request.headers
            .get("if-modified-since")
            .flatMap(_.headOption)
            .flatMap(HttpDates.parseEpochSeconds)
            .exists(_ >= lastUpdateAt)

        if notModifiedSinceLastUpdate then
          cask.Response(
            Array.empty[Byte],
            statusCode = 304,
            headers = Seq(cacheControl, "Last-Modified" -> lastModified)
          )
        else
          imageService.retrieve(imageId) match {
            case Some((contentType, bytes)) =>
              cask.Response(
                bytes,
                headers = Seq("Content-Type" -> contentType.value, cacheControl, "Last-Modified" -> lastModified)
              )
            case None => cask.Response(Array.empty[Byte], statusCode = 404)
          }
    }
  }

  @cask.post("api/images/upload")
  def uploadImage(movieId: Int, recipient: String, request: cask.Request) = {
    val bytes         = request.bytes
    val maybeMimeType = for {
      httpContentType <- request.headers.get("content-type").flatMap(_.headOption).toRight(s"Missing Image content type")
      claimedMimeType <- ImageData.MimeType.fromString(httpContentType)
      derivedMimeType <- imageService.imageType(bytes).toRight("Could not derive mime type from bytes")
      _               <- Either.cond(
        derivedMimeType == claimedMimeType,
        (),
        s"Claimed mime type $claimedMimeType does not correspond to derived mime type $derivedMimeType"
      )
    } yield derivedMimeType

    maybeMimeType match {
      case Left(err)       => cask.Response(err, statusCode = 400)
      case Right(mimeType) =>
        val storedImage = imageService.storeAsNewImage(mimeType, bytes)
        Await.result(
          eventSourcingService
            .entity(movieId, Movie.entityInfo)
            .ask(Movie.Command.AddImage(storedImage.id, _))
            .map {
              if _ then cask.Response(s"Image uploaded successfully with id ${storedImage.id}")
              else cask.Response(s"Movie $movieId does not seem to be active", statusCode = 400)
            }
            .recover { case ex =>
              cask.Response(s"Error while adding image to movie: ${ex.getMessage}", statusCode = 500)
            },
          30.seconds
        )
    }
  }

  initialize()

}
