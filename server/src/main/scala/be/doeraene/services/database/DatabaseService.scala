package be.doeraene.services.database

import data.images.ImageData
import tables.{*, given}
import org.flywaydb.core.Flyway
import scalasql.simple.*

import java.nio.file.{Files, Path}

class DatabaseService(dataDirectory: Path, inTest: Boolean = false) {
  import SqliteDialect.*

  Files.createDirectories(dataDirectory)

  private val sqliteDataSource = org.sqlite.SQLiteDataSource()
  sqliteDataSource.setUrl(
    s"jdbc:sqlite:${dataDirectory.resolve("database.db")}"
  )

  // noinspection TypeAnnotation
  val client: DbClient.DataSource = scalasql.DbClient.DataSource(
    sqliteDataSource,
    config = new scalasql.Config:
//      override def logSql(sql: String, file: String, line: Int) = {
//        if sql.toLowerCase.contains("case") then println(s"query: $sql")
//      }

      override def nameMapper(v: String) = Config.camelToSnake(v)

      override def columnNameMapper(v: String): String = Config.camelToSnake(v)
  )

  private lazy val db = client.getAutoCommitClientConnection

  private val flyway =
    Flyway.configure().loggers(if inTest then "slf4j" else "auto").dataSource(sqliteDataSource).load()
  flyway.migrate()

  def movies(onlyNonDeleted: Boolean = true): Vector[Movie] =
    db.run(Movie.select.filterIf(onlyNonDeleted)(_.softDeleteAt.isEmpty).sortBy(_.lastUpdateAt).desc).toVector

  def createMovie(): Movie = client.transaction { db =>
    val now                  = nowSeconds()
    val movieToInsert: Movie = Movie(-1, "Untitled", now, now, Option.empty)
    val query                = Movie.insert.values(movieToInsert).skipColumns(_.id)
    val _                    = db.run(query)
    val newId                = db.runRaw[Int]("select last_insert_rowid()").head
    movieToInsert.copy(id = newId)
  }

  def updateMovie(movie: Movie): Boolean = {
    db.run(
      Movie
        .update(_.id === movie.id)
        .set(
          _.name         := movie.name,
          _.lastUpdateAt := nowSeconds()
        )
    ) > 0
  }

  def softDeleteMovie(movieId: Movie.Id): Boolean = db.run(
    Movie.update(_.id === movieId.value).set(_.softDeleteAt := Option(nowSeconds()))
  ) > 0

  def hardDeleteMovie(movieId: Movie.Id): Unit = client.transaction { db =>
    db.run(MovieToImage.delete(_.movieId === movieId.value))
    db.run(Movie.delete(_.id === movieId.value))
  }

  def getMovie(id: Int): Option[Movie] =
    db.run(Movie.select.filter(_.id === id).take(1)).headOption

  def images: Vector[Image] = db.run(Image.select).toVector

  def createImageIfNotExists(uuid: java.util.UUID, contentType: ImageData.MimeType, bytes: Array[Byte]): Image =
    client.transaction { db =>
      val updated = db.run(
        Image
          .update(_.uuid === uuid)
          .set(
            _.contentType := contentType,
            _.image       := bytes
          )
      )

      val image = Image(uuid, contentType, bytes)
      if updated > 0 then image
      else
        db.run(Image.insert.values(image))
        image
    }

  def getImage(uuid: java.util.UUID): Option[Image] =
    db.run(Image.select.filter(_.uuid === uuid).take(1)).headOption

  private def imageExists(image: Image): Boolean = db.run(Image.select.filter(_.uuid === image.uuid).take(1)).nonEmpty

  def attachImageToMovie(image: Image, movie: Movie): Boolean = client.transaction { db =>
    if db.run(MovieToImage.select.filter(_.movieId === movie.id).filter(_.imageUUID === image.uuid).take(1)).isEmpty
    then
      if imageExists(image) && getMovie(movie.id).nonEmpty then {
        val maxIndex = db
          .run(MovieToImage.select.filter(_.movieId === movie.id).map(_.imageIndexInMovie))
          .flatten
          .maxOption
          .getOrElse(-1)
        db.run(MovieToImage.insert.values(MovieToImage(movie.id, image.uuid, imageIndexInMovie = Some(maxIndex + 1))))
        true
      } else false
    else true
  }

  /** @param indicesToUpdate
    *   map from the image id to the new index value
    * @param movieId
    *   id of the movie in which to make the change
    */
  def updateImagesIndices(indicesToUpdate: Map[ImageData.Id, Int], movieId: Movie.Id): Unit = client.transaction { db =>
    indicesToUpdate.toVector.grouped(499).foreach { toUpdateNow =>
      db.run(
        MovieToImage
          .update(link => link.movieId === movieId.value)
          .set(link =>
            link.imageIndexInMovie := db
              .caseWhen(
                toUpdateNow
                  .map((imageId, newIndex) => (link.imageUUID === imageId.uuidValue) -> Option(newIndex))*
              )
              .`else`(link.imageIndexInMovie)
          )
      )
    }
  }

  def updateImagesIndices(indicesToUpdate: Map[Int, Int], movieId: Movie.Id)(using DummyImplicit): Unit = {
    val currentImagesIdsWithIndex = db
      .run(
        MovieToImage.select.filter(_.movieId === movieId.value).map(link => (link.imageUUID, link.imageIndexInMovie))
      )
      .collect { case (imageId, Some(index)) =>
        index -> ImageData.Id.fromUUID(imageId)
      }
      .toMap

    updateImagesIndices(indicesToUpdate.map((from, to) => currentImagesIdsWithIndex(from) -> to), movieId)
  }

  /** Removes an image from a movie. It can be permanently (fullDelete) or simply remove it from movie content, but the
    * image can still be used afterward.
    *
    * @param fullDelete
    *   whether to completely forget about the image.
    * @return
    *   the number of affected links, probably 1 or 0.
    */
  def removeImageFromMovie(
      imageId: java.util.UUID,
      movie: Movie,
      fullDelete: Boolean = false
  ): DatabaseService.Affected = client.transaction { db =>
    val affected =
      if fullDelete then db.run(MovieToImage.delete(link => link.movieId === movie.id && link.imageUUID === imageId))
      else
        db.run(
          MovieToImage
            .update(link => link.movieId === movie.id && link.imageUUID === imageId)
            .set(_.imageIndexInMovie := Option.empty)
        )

    val isImageStillUsed =
      db.run(MovieToImage.select.filter(_.movieId === movie.id).filter(_.imageUUID === imageId).take(1)).nonEmpty
    if !isImageStillUsed then db.run(Image.delete(_.uuid === imageId))

    affected
  }

  def imagesInMovie(movie: Movie): Vector[(image: ImageData, maybeIndex: Option[Int])] =
    db.run(
      Image.select
        .join(MovieToImage.select.filter(_.movieId === movie.id))((image, link) => image.uuid === link.imageUUID)
        .map((image, link) => (image.uuid, link.imageIndexInMovie))
    ).toVector
      .map((id, maybeIndex) => ImageData(ImageData.Id.fromUUID(id)) -> maybeIndex)

  private[database] def deleteDatabase(): Unit = {
    db.close()
    os.remove.all(os.Path(dataDirectory.toAbsolutePath))
  }

  private def nowSeconds() = System.currentTimeMillis() / 1000

}

object DatabaseService {
  private type Affected = Int
}
