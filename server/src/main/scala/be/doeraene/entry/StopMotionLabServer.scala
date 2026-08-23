package be.doeraene.entry

import be.doeraene.services.database.DatabaseService
import be.doeraene.services.filestorage.FileStorageService
import be.doeraene.services.images.ImagesService
import cask.main.{Main, Routes}
import io.undertow.Undertow

import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import javax.net.ssl.SSLContext
import scala.util.chaining.*

object StopMotionLabServer extends cask.MainRoutes {

  given DatabaseService    = DatabaseService(Paths.get("./data/db"))
  given FileStorageService = FileStorageService(Paths.get("./data/storage"))
  given ImagesService      = ImagesService()

  def otherRoutes: Seq[cask.Routes] = Seq(VideoFluxRoutes(), ImagesRoutes())

  override def allRoutes: Seq[Routes] = super.allRoutes ++ otherRoutes

  override def host: String = "0.0.0.0"

  private lazy val cachedHandlerExecutor: Option[ExecutorService] = handlerExecutor()

  private lazy val sslContext: SSLContext = MakeSslContext()

  private lazy val isProd = Option(java.lang.System.getProperty("isProd")).fold(false)(_.toBoolean)

  override def main(args: Array[String]): Unit = {
    if (!verbose) Main.silenceJboss()
    val server = Undertow.builder
      .tap(builder =>
        if isProd then builder.addHttpsListener(port, host, sslContext) else builder.addHttpListener(port, host)
      )
      .setHandler(defaultHandler)
      .build
    server.start()
    // register an on exit hook to stop the server
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.stop()
      cachedHandlerExecutor.foreach(_.shutdown())
      executionContext.shutdown()
    }))
  }

  @cask.get("/")
  def hello() =
    "Hello World!"

  initialize()

}
