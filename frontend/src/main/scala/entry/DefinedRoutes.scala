package entry
import urldsl.errors.DummyError
import urldsl.language.PathSegment
import urldsl.language.dummyErrorImpl.*

object DefinedRoutes {
  private type Path[T] = PathSegment[T, DummyError]

  val home: Path[Unit] = root / "home"

  val phonePath: Path[Unit]       = root / "phone"
  val movieEditorPath: Path[Unit] = root / "movie-editor"

}
