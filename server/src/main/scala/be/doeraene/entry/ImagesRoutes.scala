package be.doeraene.entry

import be.doeraene.services.images.ImagesService
import data.images.ImageData

//noinspection TypeAnnotation
class ImagesRoutes(using imageService: ImagesService)(using castor.Context, cask.util.Logger) extends cask.Routes {

  @cask.get("api/images/:id")
  def imageBytes(id: java.util.UUID) =
    imageService.retrieve(ImageData.Id.fromUUID(id)) match {
      case Some((contentType, bytes)) =>
        cask.Response(bytes, headers = Seq("Content-Type" -> contentType.value))
      case None => cask.Response((), statusCode = 404)
    }

  @cask.post("api/images/upload")
  def uploadImage(movieId: Int, request: cask.Request) = {
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
        ???
    }

  }

}
