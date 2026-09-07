package be.doeraene.services.images

import be.doeraene.services.database.DatabaseService
import data.images.ImageData

import java.util.UUID

class ImagesService()(using db: DatabaseService) {

  def storeAsNewImage(contentType: ImageData.MimeType, data: Array[Byte]): ImageData = {
    val id = ImageData.Id.random()
    store(id, contentType, data)
    ImageData(id)
  }

  def store(id: ImageData.Id, contentType: ImageData.MimeType, data: Array[Byte]): Unit =
    db.createImageIfNotExists(id.uuidValue, contentType, data)
    ()

  def retrieve(id: ImageData.Id): Option[(ImageData.MimeType, Array[Byte])] =
    db.getImage(id.uuidValue)
      .map(image => (image.contentType, image.image))

  /** When the image was last created/overwritten, in epoch seconds. Cheap compared to `retrieve`, since it doesn't
    * load the image bytes; meant for HTTP cache validation.
    */
  def lastUpdated(id: ImageData.Id): Option[Long] =
    db.getImageLastUpdate(id.uuidValue)

  def imageType(bytes: Array[Byte]): Option[ImageData.MimeType] =
    if bytes.length >= 3 &&
      bytes(0) == 0xff.toByte &&
      bytes(1) == 0xd8.toByte &&
      bytes(2) == 0xff.toByte
    then {
      Some(ImageData.MimeType.Jpeg)
    } else if (
      bytes.length >= 8 &&
      bytes
        .take(8)
        .sameElements(
          Array[Byte](
            0x89.toByte,
            0x50.toByte,
            0x4e.toByte,
            0x47.toByte,
            0x0d.toByte,
            0x0a.toByte,
            0x1a.toByte,
            0x0a.toByte
          )
        )
    ) {
      Some(ImageData.MimeType.Png)
    } else {
      None
    }

}
