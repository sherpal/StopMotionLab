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
  def movies() = json(moviesService.movies)

  @cask.post("api/movies/update")
  def updateMovieName(movieId: Int, name: String) = json {
    val updated = moviesService.updateName(Movie.Id(movieId), name)
    updated
  }

  @cask.post("api/movies/create")
  def createMovie() = json {
    val id = moviesService.create()
    id
  }

  @cask.post("api/movies/delete")
  def deleteMovie(movieId: Int) = json {
    val deleted = moviesService.delete(Movie.Id(movieId))
    deleted
  }

  initialize()

}
