package be.doeraene.routes

import be.doeraene.routes.Helpers
import be.doeraene.services.movies.MoviesService
import data.movie.Movie

//noinspection TypeAnnotation
class MoviesRoutes(using moviesService: MoviesService)(using
    castor.Context,
    cask.util.Logger
) extends cask.Routes
    with Helpers {

  @cask.get("api/movies/all")
  def movies() = json(moviesService.moviesMetadata)

  @cask.get("api/movies/deleted")
  def deletedMovies() = json(moviesService.deletedMoviesMetadata)

  @cask.post("api/movies/create")
  def createMovie() = json {
    val id = moviesService.create()
    id
  }

  initialize()

}
