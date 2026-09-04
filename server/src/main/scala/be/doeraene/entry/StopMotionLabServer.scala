package be.doeraene.entry

import be.doeraene.routes.{CommandRoutes, ImagesRoutes, MoviesRoutes, VideoFluxRoutes}
import be.doeraene.services.connectedclients.ConnectedClientsService
import be.doeraene.services.database.DatabaseService
import be.doeraene.services.filestorage.FileStorageService
import be.doeraene.services.images.ImagesService
import be.doeraene.services.movies.MoviesService
import cask.main.{Main, Routes}
import eventsourcing.EventSourcingService
import io.undertow.Undertow

import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import javax.net.ssl.SSLContext
import scala.util.chaining.*

object StopMotionLabServer extends cask.MainRoutes {

  given DatabaseService      = DatabaseService(Paths.get("./data/db"))
  given EventSourcingService = EventSourcingService(EventSourcingService.Config.default, summon[DatabaseService].client)
  given FileStorageService   = FileStorageService(Paths.get("./data/storage"))
  given ImagesService        = ImagesService()
  given MoviesService        = MoviesService()
  given ConnectedClientsService = ConnectedClientsService()

  def otherRoutes: Seq[cask.Routes] =
    Seq(VideoFluxRoutes(), ImagesRoutes(), MoviesRoutes(), CommandRoutes(Seq(summon[MoviesService].commandRouter)))

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
    Runtime.getRuntime.addShutdownHook(Thread(() => {
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
