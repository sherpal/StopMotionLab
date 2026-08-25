package be.doeraene.routes

import be.doeraene.services.connectedclients.ConnectedClientsService
import be.doeraene.services.images.ImagesService
import data.images.ImageData
import data.movie.Movie

//noinspection TypeAnnotation
class ImagesRoutes(using imageService: ImagesService, connectedClientsService: ConnectedClientsService)(using
    castor.Context,
    cask.util.Logger
) extends cask.Routes {

  @cask.get("api/images/:id")
  def imageBytes(id: String) =
    imageService.retrieve(ImageData.Id.fromUUID(java.util.UUID.fromString(id))) match {
      case Some((contentType, bytes)) =>
        cask.Response(bytes, headers = Seq("Content-Type" -> contentType.value))
      case None => cask.Response(Array.empty[Byte], statusCode = 404)
    }

  @cask.post("api/images/upload")
  def uploadImage(movieId: Int, recipient: String, request: cask.Request) = {
    val bytes         = request.bytes
    val maybeMimeType = for {
      httpContentType <- request.httpContentType.toRight(s"Missing Image content type")
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
        connectedClientsService.imageUploaded(Movie.Id(movieId), java.util.UUID.fromString(recipient), storedImage)

        cask.Response("")
    }
  }

  initialize()

}
