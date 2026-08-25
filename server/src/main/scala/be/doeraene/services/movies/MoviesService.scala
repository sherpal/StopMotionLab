package be.doeraene.services.movies

import be.doeraene.services.database.DatabaseService
import data.movie.Movie

class MoviesService()(using db: DatabaseService) {

  def movies: Vector[Movie] = db.movies().map { dbMovie =>
    val images = db.imagesInMovie(dbMovie)
    Movie(
      Movie.Id(dbMovie.id),
      dbMovie.name,
      images.map(Movie.ImageDataWithOrdering(_, _)),
      createdAt = dbMovie.createdAt,
      lastUpdatedAt = dbMovie.lastUpdateAt,
      deleted = dbMovie.softDeleteAt.isDefined
    )
  }

  def updateName(id: Movie.Id, newName: String): Boolean =
    db.getMovie(id.value).exists(movie => db.updateMovie(movie.copy(name = newName)))

  def create(): Movie.Id =
    Movie.Id(db.createMovie().id)
    
  def delete(id: Movie.Id): Boolean =    db.softDeleteMovie(id)

}
