package be.doeraene.utils

import java.net.{Inet4Address, NetworkInterface}
import scala.jdk.CollectionConverters.*

object NetworkUtils {

  /** Best-effort guess of this machine's LAN IPv4 address, e.g. "192.168.1.23".
    *
    * Used to build a URL that a phone on the same Wi-Fi network can reach, since the server itself binds to
    * "0.0.0.0" and doesn't otherwise know which address is reachable from the outside.
    */
  def localNetworkAddress: Option[String] = {
    val candidates = for {
      iface <- NetworkInterface.getNetworkInterfaces.asScala.toList
      if iface.isUp && !iface.isLoopback && !iface.isVirtual
      addr  <- iface.getInetAddresses.asScala.toList
      if addr.isInstanceOf[Inet4Address] && !addr.isLoopbackAddress
    } yield iface.getDisplayName -> addr.getHostAddress

    // Prefer interfaces that look like Wi-Fi/Ethernet over docker/virtual-ish ones when several are up.
    def score(name: String): Int =
      if name.matches("(?i).*(wlan|wi-?fi|en0|eth).*") then 0 else 1

    candidates.sortBy { case (name, _) => score(name) }.headOption.map { case (_, ip) => ip }
  }

}
