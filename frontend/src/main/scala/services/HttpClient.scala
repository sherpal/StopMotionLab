package services

import communication.http.{ErrorADT, HttpClient}
import io.circe.{Decoder, Encoder}
import org.scalajs.dom
import io.circe.parser.decode
import org.scalajs.dom.{HttpMethod, RequestInit, fetch}

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.Int8Array

class HttpClient(maybeApiHost: Option[String])(using ExecutionContext) {
  private final class RequestFailed(code: Int) extends Exception(s"Error code: $code")

  private def decoderToBodyReader[R](decoder: Decoder[R]): String => Either[io.circe.Error, R] = {
    given Decoder[R] = decoder
    decode(_)
  }

  import HttpClient.{csrfTokenName, apiPrefix}

  private def maybeCsrfToken: Option[String] =
    dom.document.cookie
      .split(";")
      .map(_.trim)
      .find(_.startsWith(s"$csrfTokenName="))
      .map(_.drop(csrfTokenName.length + 1))

  private class RequestForResponse[R](using ec: ExecutionContext) {

    def apply[Q](
        method: HttpMethod,
        path: HttpClient.Path[Unit],
        maybeQuery: Option[(HttpClient.Query[Q], Q)],
        body: Option[String],
        bodyReader: String => Either[io.circe.Error, R]
    ): Future[(R, Int)] =
      apply(method, path, maybeQuery, body, (), bodyReader)

    def apply[T, Q](
        method: HttpMethod,
        path: HttpClient.Path[T],
        maybeQuery: Option[(HttpClient.Query[Q], Q)],
        maybeBody: Option[String],
        pathArg: T,
        bodyReader: String => Either[io.circe.Error, R]
    ): Future[(R, Int)] = {

      val queryString = maybeQuery.fold("") { case (query, q) =>
        "?" ++ query.createParamsString(q)
      }

      val url =
        maybeApiHost.fold(dom.document.location.origin)(_.stripSuffix("/")) ++
          s"/$apiPrefix/" ++
          path.createPath(pathArg) ++
          queryString

      val maybeCsrf = maybeCsrfToken

      val requestInit = {
        val ri = new RequestInit {}
        ri.method = method
        ri.body = maybeBody.orUndefined

        ri.headers = (
          Map() ++
            maybeBody.fold(Map.empty[String, String])(_ => Map("Content-Type" -> "application/json")) ++
            maybeCsrf
              .map(csrfTokenName -> _)
              .filter(_ => method != HttpMethod.GET)
              .toMap
        ).toJSDictionary

        ri
      }

      fetch(url, requestInit).toFuture
        .flatMap { response =>
          response
            .text()
            .toFuture
            .flatMap { text =>
              val status = response.status

              if !response.ok then {
                decode[ErrorADT](text) match {
                  case Right(error) =>
                    Future.failed(ErrorADT.AsException(error))
                  case Left(_) =>
                    Future.failed(
                      RuntimeException(
                        s"$status: Failed when calling $url. Response body was $text"
                      )
                    )
                }
              } else {
                bodyReader(text) match {
                  case Right(r) =>
                    Future.successful((r, status))
                  case Left(error) =>
                    Future.failed(error)
                }
              }
            }
        }
    }
  }

  private def send[R](using ExecutionContext) =
    new RequestForResponse[R]

  def getStatus(path: HttpClient.Path[Unit])(using ExecutionContext): Future[Int] =
    send[Unit](HttpMethod.GET, path, None, None, _ => Right(())).map(_._2)

  def get[R](using ExecutionContext): GETResponseFilled[R] =
    new GETResponseFilled[R] {

      def bytes[T](path: HttpClient.Path[T])(t: T)(using ExecutionContext): Future[(String, Array[Byte])] = {
        val url =
          maybeApiHost.fold(dom.document.location.origin)(_.stripSuffix("/")) ++
            s"/$apiPrefix/" ++
            path.createPath(t)

        fetch(url, new RequestInit { method = HttpMethod.GET }).toFuture.flatMap { response =>
          if !response.ok then
            response.text().toFuture.flatMap(err => Future.failed(RuntimeException(s"${response.status}: $err")))
          else
            response.arrayBuffer().toFuture.map { buffer =>
              val bytes = Int8Array(buffer).toArray[Byte]

              val contentType = Option(response.headers.get("Content-Type")).getOrElse("application/octet-stream")
              (contentType, bytes)
            }
        }
      }

      def apply[T, Q](
          path: HttpClient.Path[T],
          query: HttpClient.Query[Q]
      )(t: T, q: Q)(using decoder: Decoder[R]): Future[R] =
        send[R](
          HttpMethod.GET,
          path,
          Some((query, q)),
          None,
          t,
          decoderToBodyReader(decoder)
        ).map(_._1)

      def apply(
          path: HttpClient.Path[Unit]
      )(using decoder: Decoder[R]): Future[R] =
        send[R](
          HttpMethod.GET,
          path,
          None,
          None,
          decoderToBodyReader(decoder)
        ).map(_._1)

      def apply[Q](
          path: HttpClient.Path[Unit],
          query: HttpClient.Query[Q]
      )(q: Q)(using decoder: Decoder[R]): Future[R] =
        send[R](
          HttpMethod.GET,
          path,
          Some((query, q)),
          None,
          decoderToBodyReader(decoder)
        ).map(_._1)
    }

  def post[R](using ExecutionContext): POSTResponseFilled[R] =
    new POSTResponseFilled[R] {

      def apply[Q](
          path: HttpClient.Path[Unit],
          query: HttpClient.Query[Q]
      )(q: Q)(using decoder: Decoder[R]): Future[R] =
        send[R](
          HttpMethod.POST,
          path,
          Some((query, q)),
          None,
          decoderToBodyReader(decoder)
        ).map(_._1)

      def apply[B, Q](
          path: HttpClient.Path[Unit],
          query: HttpClient.Query[Q],
          body: B
      )(q: Q)(using
          decoder: Decoder[R],
          encoder: Encoder[B]
      ): Future[R] =
        send[R](
          HttpMethod.POST,
          path,
          Some((query, q)),
          Some(encoder(body).noSpaces),
          decoderToBodyReader(decoder)
        ).map(_._1)

      def apply[B](
          path: HttpClient.Path[Unit],
          body: B
      )(using
          decoder: Decoder[R],
          encoder: Encoder[B]
      ): Future[R] =
        send[R](
          HttpMethod.POST,
          path,
          None,
          Some(encoder(body).noSpaces),
          decoderToBodyReader(decoder)
        ).map(_._1)
    }

  def postIgnore[Q](
      path: HttpClient.Path[Unit],
      query: HttpClient.Query[Q]
  )(q: Q)(using ExecutionContext): Future[Int] =
    send[Unit](
      HttpMethod.POST,
      path,
      Some((query, q)),
      None,
      _ => Right(())
    ).map(_._2)

  def postIgnore[B](
      path: HttpClient.Path[Unit],
      body: B
  )(using
      encoder: Encoder[B],
      ec: ExecutionContext
  ): Future[Int] =
    send[Unit](
      HttpMethod.POST,
      path,
      None,
      Some(encoder(body).noSpaces),
      _ => Right(())
    ).map(_._2)

  def postIgnore[B, Q](
      path: HttpClient.Path[Unit],
      query: HttpClient.Query[Q],
      body: B
  )(q: Q)(using
      encoder: Encoder[B],
      ec: ExecutionContext
  ): Future[Int] =
    send[Unit](
      HttpMethod.POST,
      path,
      Some((query, q)),
      Some(encoder(body).noSpaces),
      _ => Right(())
    ).map(_._2)

  trait GETResponseFilled[R] {

    /** Makes a GET http call to the given [[Path]] with the given [[Query]] parameters. Interpret the response as an
      * element of type `R`.
      */
    def apply[T, Q](path: HttpClient.Path[T], query: HttpClient.Query[Q])(t: T, q: Q)(using
        decoder: Decoder[R]
    ): Future[R]

    /** Makes a GET http call to the given [[Path]]. Interpret the response as an element of type `R`.
      */
    def apply(path: HttpClient.Path[Unit])(using decoder: Decoder[R]): Future[R]

    /** Makes a GET http call to the given [[Path]] with the given [[Query]] parameters. Interpret the reponse as an
      * element of type `R`.
      */
    def apply[Q](path: HttpClient.Path[Unit], query: HttpClient.Query[Q])(q: Q)(using decoder: Decoder[R]): Future[R]

    /** Makes a GET http call to the given [[Path]] and returns the raw bytes of the response. */
    def bytes[T](path: HttpClient.Path[T])(t: T)(using ExecutionContext): Future[(String, Array[Byte])]

  }

  trait POSTResponseFilled[R] {

    /** Makes a POST http call to the given [[Path]] with the given [[Query]] parameters, without body. Interpret the
      * response as an element of type `R`.
      */
    def apply[Q](path: HttpClient.Path[Unit], query: HttpClient.Query[Q])(q: Q)(using decoder: Decoder[R]): Future[R]

    /** Makes a POST http call to the given [[Path]] with the given [[Query]] parameters and with the given body.
      * Interpret the response as an element of type `R`.
      */
    def apply[B, Q](path: HttpClient.Path[Unit], query: HttpClient.Query[Q], body: B)(
        q: Q
    )(using decoder: Decoder[R], encoder: Encoder[B]): Future[R]

    def apply[B](path: HttpClient.Path[Unit], body: B)(using decoder: Decoder[R], encoder: Encoder[B]): Future[R]
  }

}
