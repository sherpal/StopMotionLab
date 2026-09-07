package be.doeraene.utils

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.util.Try

/** Formatting/parsing helpers for the HTTP-date format used by headers such as `Last-Modified` and
  * `If-Modified-Since` (RFC 7231 §7.1.1.1).
  */
object HttpDates {

  private val formatter = DateTimeFormatter.RFC_1123_DATE_TIME

  /** Formats a unix timestamp (in epoch seconds) as an HTTP-date, suitable for a `Last-Modified` header. */
  def format(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).format(formatter)

  /** Parses an HTTP-date, e.g. from an `If-Modified-Since` request header, back to epoch seconds. */
  def parseEpochSeconds(httpDate: String): Option[Long] =
    Try(ZonedDateTime.parse(httpDate, formatter).toEpochSecond).toOption

}
