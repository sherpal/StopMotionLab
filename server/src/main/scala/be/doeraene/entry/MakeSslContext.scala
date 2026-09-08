package be.doeraene.entry

import be.doeraene.tls.{LocalServerCertificate, SelfSignedCertificateAuthority}
import be.doeraene.utils.NetworkUtils

import java.nio.file.Paths
import javax.net.ssl.SSLContext

/** Builds the TLS context the server presents to phones on the LAN.
  *
  * There's no way to get a certificate the public web PKI vouches for a private LAN IP, so instead this mints its
  * own tiny certificate authority once (cached under `./data/certs`) and, from it, a leaf certificate scoped to
  * whatever LAN IP this machine currently has. A phone that installs the CA's public cert once (see
  * `PhoneConnectionRoutes`'s `/api/ca-cert`) trusts every future leaf automatically, even ones re-issued because the
  * machine moved to a different Wi-Fi network -- no more hand-generating certs per IP, and no re-trusting after the
  * first time.
  */
private[entry] object MakeSslContext {

  private val certsDir = Paths.get("./data/certs")

  def apply(): SSLContext = {
    val ip = NetworkUtils.localNetworkAddress.getOrElse {
      println("[MakeSslContext] No LAN address detected; falling back to 'localhost' for the server certificate.")
      "127.0.0.1"
    }

    val (justCreated, ca) = SelfSignedCertificateAuthority.loadOrCreate(certsDir)
    if (justCreated) {
      println(
        s"""[MakeSslContext] Generated a new local certificate authority at $certsDir/ca.crt.
           |For phones to connect without a browser warning, install that file as a trusted root once
           |(it's also served at GET /api/ca-cert once the server is running).""".stripMargin
      )
    }

    LocalServerCertificate.sslContextFor(certsDir, ca, ip)
  }

}
