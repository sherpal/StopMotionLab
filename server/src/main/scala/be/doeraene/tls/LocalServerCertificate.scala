package be.doeraene.tls

import be.doeraene.tls.SelfSignedCertificateAuthority.CertificateAuthority
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{Extension, GeneralName, GeneralNames}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.io.{FileInputStream, FileOutputStream}
import java.math.BigInteger
import java.nio.file.{Files, Path}
import java.security.cert.X509Certificate
import java.security.{KeyPairGenerator, KeyStore, SecureRandom}
import java.time.{Duration, Instant}
import java.util.Date
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The certificate the server actually presents to a phone's browser: a short-lived leaf cert, scoped to whatever
  * LAN IP this machine currently has, signed by the [[SelfSignedCertificateAuthority]] so that a phone which has
  * trusted that CA once accepts every future leaf transparently -- including one re-issued because the machine
  * changed Wi-Fi networks and got a new IP.
  */
object LocalServerCertificate {

  private val password  = "stopmotion-lab-server".toCharArray
  private val alias     = "stopmotion-lab-server"
  private val validity  = Duration.ofDays(397) // under the ~398-day cap iOS/Android enforce for leaf certs
  private val renewIfExpiringWithin = Duration.ofDays(30)
  private val keyLength  = 2048

  /** Builds an [[SSLContext]] presenting a certificate valid for `ip`, minting one (and persisting it to
    * `dir/server.p12`) if the cached one doesn't cover that IP or is close to expiring.
    */
  def sslContextFor(dir: Path, ca: CertificateAuthority, ip: String): SSLContext = {
    val keystoreFile = dir.resolve("server.p12")

    val (leafKey, leafCert) = loadIfStillValid(keystoreFile, ip) match {
      case Some(existing) => existing
      case None           =>
        val minted = create(ca, ip)
        persist(keystoreFile, minted)
        minted
    }

    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null, null)
    keyStore.setKeyEntry(alias, leafKey, password, Array(leafCert, ca.certificate))

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, password)

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, null, null)
    sslContext
  }

  private def loadIfStillValid(
      keystoreFile: Path,
      ip: String
  ): Option[(java.security.PrivateKey, X509Certificate)] = {
    if (!Files.exists(keystoreFile)) None
    else {
      val keyStore = KeyStore.getInstance("PKCS12")
      Using.resource(FileInputStream(keystoreFile.toFile))(keyStore.load(_, password))
      val cert = keyStore.getCertificate(alias).asInstanceOf[X509Certificate]
      val key  = keyStore.getKey(alias, password).asInstanceOf[java.security.PrivateKey]

      val coversIp  = subjectAlternativeIps(cert).contains(ip)
      val notExpiring = cert.getNotAfter.toInstant.isAfter(Instant.now.plus(renewIfExpiringWithin))

      Option.when(coversIp && notExpiring)((key, cert))
    }
  }

  private def subjectAlternativeIps(cert: X509Certificate): Set[String] =
    Option(cert.getSubjectAlternativeNames)
      .map(_.asScala.collect {
        case entry if entry.get(0) == GeneralName.iPAddress => entry.get(1).asInstanceOf[String]
      }.toSet)
      .getOrElse(Set.empty)

  private def persist(keystoreFile: Path, leaf: (java.security.PrivateKey, X509Certificate)): Unit = {
    val (key, cert) = leaf
    val keyStore     = KeyStore.getInstance("PKCS12")
    keyStore.load(null, null)
    keyStore.setKeyEntry(alias, key, password, Array(cert))
    Using.resource(FileOutputStream(keystoreFile.toFile))(keyStore.store(_, password))
  }

  private def create(ca: CertificateAuthority, ip: String): (java.security.PrivateKey, X509Certificate) = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(keyLength, SecureRandom())
    val keyPair = keyPairGenerator.generateKeyPair()

    val subject   = X500Name(s"CN=$ip")
    val issuer    = X500Name(ca.certificate.getSubjectX500Principal.getName)
    val now       = Instant.now()
    val serial    = BigInteger.valueOf(now.toEpochMilli)
    val notBefore = Date.from(now.minus(Duration.ofDays(1)))
    val notAfter  = Date.from(now.plus(validity))

    val sanNames = GeneralNames(
      Array(
        GeneralName(GeneralName.iPAddress, ip),
        GeneralName(GeneralName.iPAddress, "127.0.0.1"),
        GeneralName(GeneralName.dNSName, "localhost")
      )
    )

    val certificateBuilder =
      JcaX509v3CertificateBuilder(issuer, serial, notBefore, notAfter, subject, keyPair.getPublic)
        .addExtension(Extension.subjectAlternativeName, false, sanNames)

    val signer      = JcaContentSignerBuilder("SHA256withRSA").build(ca.privateKey)
    val certificate = JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer))

    (keyPair.getPrivate, certificate)
  }

}
