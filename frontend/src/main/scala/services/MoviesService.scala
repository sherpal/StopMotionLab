package services

import data.movie.Movie
import urldsl.language.dummyErrorImpl.*

import scala.concurrent.{ExecutionContext, Future}

class MoviesService(using httpClient: HttpClient)(using ExecutionContext) {

  private val moviesPath = root / "movies"

  def movies: Future[Vector[Movie]] = httpClient.get[Vector[Movie]](moviesPath / "all")

  def create(): Future[Movie.Id] = httpClient.post[Movie.Id](moviesPath / "create", ignore)(())

  def delete(id: Movie.Id): Future[Boolean] =
    httpClient.post[Boolean](moviesPath / "delete", param[Movie.Id]("movieId"))(id)

}
