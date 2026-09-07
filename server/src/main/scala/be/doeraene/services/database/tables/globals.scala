package be.doeraene.services.database.tables

import data.images.ImageData
import scalasql.simple.{SimpleTable, TypeMapper}

import java.sql.{JDBCType, PreparedStatement, ResultSet}

given TypeMapper[Array[Byte]] = new TypeMapper[Array[Byte]] {
  def jdbcType = JDBCType.BLOB

  def get(r: ResultSet, idx: Int): Array[Byte] = r.getBytes(idx)

  def put(r: PreparedStatement, idx: Int, v: Array[Byte]): Unit = r.setBytes(idx, v)
}

given (using strMapper: TypeMapper[String]): TypeMapper[ImageData.MimeType] =
  strMapper.bimap(_.value, ImageData.MimeType.unsafeFromString)

case class Movie(id: Int, name: String, createdAt: Long, lastUpdateAt: Long)
object Movie extends SimpleTable[Movie] {
  type Id = data.movie.Movie.Id

  extension (movie: Movie) {
    def typedId: Id = data.movie.Movie.Id(movie.id)
  }
}

case class Image(uuid: java.util.UUID, contentType: ImageData.MimeType, image: Array[Byte], lastUpdateAt: Long):
  override def equals(obj: Any): Boolean = obj match {
    case that: Image =>
      this.uuid == that.uuid && this.contentType == that.contentType && this.image.length == that.image.length && this.image.indices
        .forall(j => this.image(j) == that.image(j))
    case that: Any => false
  }
object Image extends SimpleTable[Image]

case class MovieToImage(movieId: Int, imageUUID: java.util.UUID, imageIndexInMovie: Option[Int])
object MovieToImage extends SimpleTable[MovieToImage]

case class RawEventEnvelope(
    offset: Int,
    entityId: Int,
    sequenceNumber: Int,
    eventPayload: String,
    entityKind: String,
    timestamp: Long
)
object RawEventEnvelope extends SimpleTable[RawEventEnvelope]

case class ProjectionCheckpoint(projectionName: String, lastOffset: Int, updatedAt: Long)
object ProjectionCheckpoint extends SimpleTable[ProjectionCheckpoint]
