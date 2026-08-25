package be.doeraene.services.database

import data.images.ImageData
import tables.{Image, Movie}
import munit.internal.io.PlatformIO.Paths

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

class DatabaseServiceTest extends munit.FunSuite {

  val db = FunFixture[DatabaseService](
    setup = { test =>
      DatabaseService(Paths.get("./test-data/db"), inTest = true)
    },
    teardown = { service =>
      service.deleteDatabase()
    }
  )

  db.test("Empty movies!") { service =>
    val returned = service.movies()
    assertEquals(returned, Vector.empty[Movie])
  }

  db.test("I can insert movies and get them") { service =>
    val created = service.createMovie()

    assertEquals(
      created,
      Movie(
        id = created.id,
        name = "Untitled",
        createdAt = created.createdAt,
        lastUpdateAt = created.createdAt,
        softDeleteAt = None
      )
    )
    assert(created.id.longValue > 0)

    (1 to 5).foreach(_ => service.createMovie())

    val returned = service.getMovie(created.id)
    assertEquals(returned, Some(created))

    assertEquals(service.movies().map(_.id).distinct.length, 6)
  }

  db.test("I can update a movie's name") { service =>
    val created = service.createMovie()
    Thread.sleep(1200)
    service.updateMovie(created.copy(name = "Other Name"))
    val movieNow = service.getMovie(created.id).get
    assertEquals(movieNow.name, "Other Name")
    assert(movieNow.lastUpdateAt > movieNow.createdAt)
  }

  db.test("I can insert images and set their mime type") { service =>
    val id = java.util.UUID.randomUUID()
    service.createImageIfNotExists(id, ImageData.MimeType.Png, Array(1, 2, 3))

    val returnedImage = service.getImage(id)
    assertEquals(returnedImage.map(_.contentType), Some(ImageData.MimeType.Png))
    assertEquals(returnedImage.map(_.image.toVector), Some(Vector[Byte](1, 2, 3)))
  }

  db.test("I can insert images and attach them to movies") { service =>
    import scala.concurrent.ExecutionContext.Implicits.global
    val movies = (1 to 10).toVector.map(_ => Future(service.createMovie())).map(Await.result(_, 2.seconds))

    assertEquals(movies.map(_.id).distinct.length, 10)

    val images = (1 to 200).toVector
      .map(_ => java.util.UUID.randomUUID())
      .map(service.createImageIfNotExists(_, ImageData.MimeType.Png, Array.empty))

    def multiplesOf(n: Int): Vector[Int] = LazyList.from(0).map(_ * n).takeWhile(_ < images.length).toVector

    for {
      (movie, index) <- movies.zipWithIndex
      imageIndex     <- multiplesOf(index + 3)
      image = images(imageIndex)
    } do {
      val isAttached = service.attachImageToMovie(image, movie)
      assertEquals(isAttached, true)
    }

    for {
      (movie, index) <- movies.zipWithIndex
    } {
      val returnedImagesInMovie = service.imagesInMovie(movie).sortBy(_.image.id.uuidValue.toString)
      val expectedImagesInMovie = multiplesOf(index + 3).map(images(_)).sortBy(_.uuid.toString)

      assertEquals(returnedImagesInMovie.length, expectedImagesInMovie.length, clue = s"$movie with index $index")
      assertEquals(
        returnedImagesInMovie.map(_.image),
        expectedImagesInMovie.map(image => ImageData(ImageData.Id.fromUUID(image.uuid))),
        clue = s"$movie with index $index"
      )
      assertEquals(returnedImagesInMovie.flatMap(_.maybeIndex).sorted, returnedImagesInMovie.indices.toVector)
    }
  }

  db.test("I can change the ordering of images in a movie") { service =>
    val movie  = service.createMovie()
    val images = (1 to 20)
      .map(_ => java.util.UUID.randomUUID())
      .map(service.createImageIfNotExists(_, ImageData.MimeType.Png, Array.empty))
      .toVector
    images.foreach(service.attachImageToMovie(_, movie))

    val imagesInMovieNow = service.imagesInMovie(movie).sortBy(_.maybeIndex)

    assertEquals(imagesInMovieNow.flatMap(_.maybeIndex), imagesInMovieNow.indices.toVector)

    // swapping 0 and 1
    service.updateImagesIndices(
      Map(1 -> 0, 0 -> 1),
      movie.typedId
    )

    val imagesInMovieAfter = service.imagesInMovie(movie).sortBy(_.maybeIndex)

    assertEquals(imagesInMovieAfter.map(_.image)(0), imagesInMovieNow.map(_.image)(1))
    assertEquals(imagesInMovieAfter.map(_.image)(1), imagesInMovieNow.map(_.image)(0))

    val aSettingMap = imagesInMovieAfter
      .sortBy(_.image.id.uuidValue)
      .map(_.maybeIndex)
      .collect { case Some(index) => index }
      .zipWithIndex
      .toMap

    service.updateImagesIndices(aSettingMap, movie.typedId)

    val imagesInMovieEnd = service.imagesInMovie(movie)

    assertEquals(
      imagesInMovieEnd.sortBy(_.image.id.uuidValue),
      imagesInMovieAfter
        .sortBy(_.image.id.uuidValue)
        .map((image, maybeIndex) => (image, Option(aSettingMap(maybeIndex.get))))
    )
  }

}
