package be.doeraene.entry

import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext}

private[entry] object MakeSslContext {

  def apply(): SSLContext = {
    val password = "stopmotion"

    val keyStore = KeyStore.getInstance("PKCS12")
    val input    = FileInputStream("certs/server.p12")

    try {
      keyStore.load(input, password.toCharArray)
    } finally {
      input.close()
    }

    val keyManagerFactory =
      KeyManagerFactory.getInstance(
        KeyManagerFactory.getDefaultAlgorithm
      )

    keyManagerFactory.init(
      keyStore,
      password.toCharArray
    )

    val sslContext = SSLContext.getInstance("TLS")

    sslContext.init(
      keyManagerFactory.getKeyManagers,
      null,
      null
    )

    sslContext
  }

}
