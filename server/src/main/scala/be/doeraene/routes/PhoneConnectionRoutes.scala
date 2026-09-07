package be.doeraene.routes

import be.doeraene.qrcode.QrCode
import be.doeraene.utils.NetworkUtils

/** Lets the "computer" side of the app find out how a phone on the same Wi-Fi network can reach it, so it can
  * display a QR code that a phone camera can scan to open the pairing page directly.
  */
//noinspection TypeAnnotation
class PhoneConnectionRoutes(using cask.util.Logger) extends cask.Routes with Helpers {

  // The scheme/port a phone should hit are overridable, since in dev the browser-facing origin is the Vite dev
  // server (fronting this backend), not this process's own bind port.
  private def publicOrigin: Option[String] = {
    val scheme = sys.props.getOrElse("publicScheme", "https")
    val port   = sys.props.getOrElse("publicPort", "8443")
    NetworkUtils.localNetworkAddress.map(ip => s"$scheme://$ip:$port")
  }

  // The frontend is built with Vite's `base` set to "/static/" (see frontend/vite.config.js), so every app route,
  // including "/phone", is actually served under that prefix. Overridable in case that base ever changes.
  private def basePath: String = "/" ++ sys.props.getOrElse("publicBasePath", "static").stripPrefix("/").stripSuffix("/")

  private def phoneConnectUrl(editorId: String): Option[String] =
    publicOrigin.map(origin => s"$origin$basePath/phone?editorId=$editorId")

  @cask.get("api/phone-connect-info")
  def phoneConnectInfo(editorId: String) = phoneConnectUrl(editorId) match {
    case None      => cask.Response("no LAN address found", statusCode = 503)
    case Some(url) => json(url)
  }

  @cask.get("api/phone-connect-qrcode")
  def phoneConnectQrcode(editorId: String) = phoneConnectUrl(editorId) match {
    case None      => cask.Response("no LAN address found", statusCode = 503)
    case Some(url) => cask.Response(QrCode.svg(url), headers = Seq("Content-Type" -> "image/svg+xml"))
  }

  initialize()

}
