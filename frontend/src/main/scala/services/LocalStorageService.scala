package services

import io.circe.{Decoder, Encoder}
import io.circe.syntax.*
import org.scalajs.dom
import services.LocalStorageService.StoredValue
import io.circe.parser.decode

class LocalStorageService {

  def store[T](key: LocalStorageService.Key[T], value: T)(using Encoder[T]): Unit =
    storage.setItem(key.value, StoredValue(System.currentTimeMillis(), value).asJson.noSpaces)

  def retrieve[T](key: LocalStorageService.Key[T])(using Decoder[T]): Option[T] = {
    for {
      raw         <- Option(storage.getItem(key.value))
      storedValue <- decode[StoredValue[T]](raw).toOption
    } yield storedValue.value
  }

  private lazy val storage = dom.window.localStorage

}

object LocalStorageService {

  opaque type Key[T] = String
  object Key:
    inline def apply[T](value: String): Key[T] = value

    extension [T](key: Key[T]) inline def value: String = key

  private case class StoredValue[T](
      at: Long,
      value: T
  )

  private object StoredValue {
    given [T](using Encoder[T]): Encoder[StoredValue[T]] = io.circe.generic.semiauto.deriveEncoder
    given [T](using Decoder[T]): Decoder[StoredValue[T]] = io.circe.generic.semiauto.deriveDecoder
  }

}
