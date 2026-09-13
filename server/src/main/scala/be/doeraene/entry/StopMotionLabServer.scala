package be.doeraene.entry

import be.doeraene.routes.{
  CommandRoutes,
  ImagesRoutes,
  MoviesRoutes,
  PhoneConnectionRoutes,
  StaticResourcesWithContentType,
  VideoFluxRoutes
}
import be.doeraene.services.connectedclients.ConnectedClientsService
import be.doeraene.services.database.DatabaseService
import be.doeraene.services.filestorage.FileStorageService
import be.doeraene.services.images.ImagesService
import be.doeraene.services.movies.MoviesService
import cask.main.{Main, Routes}
import data.app.AppConfig
import eventsourcing.EventSourcingService
import io.undertow.Undertow

import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import javax.net.ssl.SSLContext
import scala.util.chaining.*

//noinspection TypeAnnotation
object StopMotionLabServer extends cask.MainRoutes {

  private lazy val appConfig = AppConfig(
    isProd = Option(java.lang.System.getProperty("isProd")).fold(false)(_.toBoolean),
    port = port
  )

  given DatabaseService      = DatabaseService(Paths.get("./data/db"))
  given EventSourcingService =
    EventSourcingService(
      EventSourcingService.Config.default,
      eventsourcing.SqlEventStore(summon[DatabaseService].client),
      eventsourcing.CastorScheduler()
    )
  given FileStorageService      = FileStorageService(Paths.get("./data/storage"))
  given ImagesService           = ImagesService()
  given MoviesService           = MoviesService()
  given ConnectedClientsService = ConnectedClientsService()

  private def otherRoutes: Seq[cask.Routes] =
    Seq(
      VideoFluxRoutes(),
      ImagesRoutes(),
      MoviesRoutes(),
      PhoneConnectionRoutes(appConfig),
      CommandRoutes(Seq(summon[MoviesService].commandRouter))
    )

  override def allRoutes: Seq[Routes] = super.allRoutes ++ otherRoutes

  override def host: String = "0.0.0.0"

  private lazy val cachedHandlerExecutor: Option[ExecutorService] = handlerExecutor()

  private lazy val sslContext: SSLContext = MakeSslContext()

  override def main(args: Array[String]): Unit = {
    if (!verbose) Main.silenceJboss()
    val server = Undertow.builder
      .tap(builder =>
        if appConfig.isProd then builder.addHttpsListener(appConfig.port, host, sslContext)
        else builder.addHttpListener(appConfig.port, host)
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

  private def indexHtmlStaticResource = cask.StaticResource(
    "static/index.html",
    getClass.getClassLoader,
    List("Content-Type" -> "text/html; charset=utf-8")
  )

  @StaticResourcesWithContentType("/static", indexHtmlStaticResource)
  def staticResourceRoutes() = "static"

  @cask.get("/")
  def index() = cask.Redirect("/static")

  initialize()

}
