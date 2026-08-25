package communication.http

import urldsl.errors.DummyError
import urldsl.language.{PathSegment, QueryParameters}

object HttpClient {
  type Path[T]  = PathSegment[T, DummyError]
  type Query[Q] = QueryParameters[Q, DummyError]
  inline def csrfTokenName: String = "Csrf-Token"
  inline def apiPrefix: "api"      = "api"
}
