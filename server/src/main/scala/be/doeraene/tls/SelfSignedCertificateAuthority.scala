package be.doeraene.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.{BasicConstraints, Extension, KeyUsage}
import org.bouncycastle.cert.jcajce.{JcaX509CertificateConverter, JcaX509v3CertificateBuilder}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.io.{FileInputStream, FileOutputStream, StringWriter}
import java.math.BigInteger
import java.nio.file.{Files, Path}
import java.security.cert.X509Certificate
import java.security.{KeyPairGenerator, KeyStore, PrivateKey, SecureRandom, Security}
import java.time.{Duration, Instant}
import java.util.Date
import scala.util.Using

/** A root certificate authority that exists only for this app: it never talks to the outside world, its only job is
  * to sign the leaf certificates minted by [[LocalServerCertificate]] so that once a phone trusts this one root
  * (a one-time manual step), every future leaf cert -- even one re-issued for a brand new LAN IP -- is trusted
  * automatically, with no further action on the phone.
  *
  * Generated once and cached to disk on first run, exactly like `DatabaseService`/`FileStorageService` cache their
  * own state under `./data`.
  */
object SelfSignedCertificateAuthority {

  Security.addProvider(new BouncyCastleProvider())

  private val password  = "stopmotion-lab-ca".toCharArray
  private val alias     = "stopmotion-lab-ca"
  private val validity  = Duration.ofDays(3650) // 10 years: reinstalling this root on the phone is the annoying step
  private val keyLength = 2048

  final case class CertificateAuthority(privateKey: PrivateKey, certificate: X509Certificate)

  /** Loads the CA from `dir/ca.p12` if present, otherwise mints a brand new one and persists it there, alongside a
    * plain `dir/ca.crt` PEM export meant to be installed on a phone (see `PhoneConnectionRoutes`'s `/api/ca-cert`).
    *
    * Returns whether a new CA was minted this call, together with the CA material, so the caller can print
    * onboarding instructions only when there's actually something new to install.
    */
  def loadOrCreate(dir: Path): (Boolean, CertificateAuthority) = {
    Files.createDirectories(dir)
    val keystoreFile = dir.resolve("ca.p12")

    if (Files.exists(keystoreFile)) (false, load(keystoreFile))
    else {
      val ca = create()
      persist(keystoreFile, ca)
      exportPublicCertAsPem(dir.resolve("ca.crt"), ca.certificate)
      (true, ca)
    }
  }

  private def load(keystoreFile: Path): CertificateAuthority = {
    val keyStore = KeyStore.getInstance("PKCS12")
    Using.resource(FileInputStream(keystoreFile.toFile))(keyStore.load(_, password))
    CertificateAuthority(
      keyStore.getKey(alias, password).asInstanceOf[PrivateKey],
      keyStore.getCertificate(alias).asInstanceOf[X509Certificate]
    )
  }

  private def persist(keystoreFile: Path, ca: CertificateAuthority): Unit = {
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null, null)
    keyStore.setKeyEntry(alias, ca.privateKey, password, Array(ca.certificate))
    Using.resource(FileOutputStream(keystoreFile.toFile))(keyStore.store(_, password))
  }

  private def exportPublicCertAsPem(pemFile: Path, certificate: X509Certificate): Unit = {
    val writer = StringWriter()
    Using.resource(JcaPEMWriter(writer))(_.writeObject(certificate))
    Files.writeString(pemFile, writer.toString)
  }

  private def create(): CertificateAuthority = {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(keyLength, SecureRandom())
    val keyPair = keyPairGenerator.generateKeyPair()

    val subject   = X500Name("CN=Stop Motion Lab Local CA")
    val now       = Instant.now()
    val serial    = BigInteger.valueOf(now.toEpochMilli)
    val notBefore = Date.from(now.minus(Duration.ofDays(1))) // small clock-skew buffer
    val notAfter  = Date.from(now.plus(validity))

    val certificateBuilder =
      JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.getPublic)
        .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))

    val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate)
    val certificate =
      JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer))

    CertificateAuthority(keyPair.getPrivate, certificate)
  }

}
