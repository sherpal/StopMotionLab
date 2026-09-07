package be.doeraene.utils

import java.net.{Inet4Address, NetworkInterface}
import scala.jdk.CollectionConverters.*

object NetworkUtils {

  // Names of adapters that are never the real LAN link, even though Java's NetworkInterface#isVirtual doesn't
  // reliably flag them (notably on Windows): Hyper-V/WSL switches, other VM hypervisors, VPNs, container
  // networking, etc. "vEthernet (WSL)" in particular contains "Ethernet" as a substring, so it would otherwise
  // outscore a real adapter under a naive "looks like Ethernet/Wi-Fi" heuristic.
  private val ignoredNamePattern =
    "(?i).*(virtual|vethernet|hyper-v|vmware|virtualbox|docker|wsl|tailscale|zerotier|nordlynx|tap|tun|ppp|bluetooth).*"

  private val preferredNamePattern = "(?i).*(wlan|wi-?fi|wireless|en0|ethernet).*"

  /** Best-effort guess of this machine's LAN IPv4 address, e.g. "192.168.1.23".
    *
    * Used to build a URL that a phone on the same Wi-Fi network can reach, since the server itself binds to
    * "0.0.0.0" and doesn't otherwise know which address is reachable from the outside. Can be overridden entirely
    * with `-DpublicHost=<ip>` when the heuristic below picks the wrong interface (VPNs, WSL, Docker...).
    */
  def localNetworkAddress: Option[String] =
    sys.props.get("publicHost").orElse(detectLocalNetworkAddress)

  private def detectLocalNetworkAddress: Option[String] = {
    val candidates = for {
      iface <- NetworkInterface.getNetworkInterfaces.asScala.toList
      if iface.isUp && !iface.isLoopback && !iface.isVirtual
      if !iface.getDisplayName.matches(ignoredNamePattern)
      addr  <- iface.getInetAddresses.asScala.toList
      if addr.isInstanceOf[Inet4Address] && !addr.isLoopbackAddress
    } yield iface.getDisplayName -> addr.getHostAddress

    // Prefer interfaces that look like Wi-Fi/Ethernet when several candidates remain.
    def score(name: String): Int = if name.matches(preferredNamePattern) then 0 else 1

    candidates.sortBy { case (name, _) => score(name) }.headOption.map { case (_, ip) => ip }
  }

}
