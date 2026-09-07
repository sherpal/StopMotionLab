package entry
import data.movie.Movie
import urldsl.errors.DummyError
import urldsl.language.{PathSegment, QueryParameters}
import urldsl.language.dummyErrorImpl.*

object DefinedRoutes {
  private type Path[T]  = PathSegment[T, DummyError]
  private type Query[Q] = QueryParameters[Q, DummyError]

  val home: Path[Unit] = root / "home"

  val phonePath: Path[Unit]           = root / "phone"
  val movieEditorPath: Path[Movie.Id] = root / "movie-editor" / segment[Movie.Id]

  // A phone reaches this by scanning the QR code shown on the computer/movie-editor page; the editorId
  // identifies which computer session it should pair its camera with.
  val editorIdParam: Query[String] = param[String]("editorId")

}
