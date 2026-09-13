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
import be.doeraene.utils.{AppPaths, NetworkUtils}
import cask.main.{Main, Routes}
import data.app.AppConfig
import eventsourcing.EventSourcingService
import io.undertow.Undertow

import java.awt.Desktop
import java.net.URI
import java.util.concurrent.ExecutorService
import javax.net.ssl.SSLContext
import scala.util.chaining.*

//noinspection TypeAnnotation
object StopMotionLabServer extends cask.MainRoutes {

  private lazy val appConfig = AppConfig(
    isProd = Option(java.lang.System.getProperty("isProd")).fold(false)(_.toBoolean),
    port = port
  )

  private lazy val dataDir = AppPaths.dataDir(appConfig.isProd)

  given DatabaseService      = DatabaseService(dataDir.resolve("db"))
  given EventSourcingService =
    EventSourcingService(
      EventSourcingService.Config.default,
      eventsourcing.SqlEventStore(summon[DatabaseService].client),
      eventsourcing.CastorScheduler()
    )
  given FileStorageService      = FileStorageService(dataDir.resolve("storage"))
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

  private lazy val sslContext: SSLContext = MakeSslContext(dataDir.resolve("certs"))

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
    // only auto-open a browser in prod: in dev, Vite is serving the actual page on its own port
    if appConfig.isProd then openBrowser()
    // register an on exit hook to stop the server
    Runtime.getRuntime.addShutdownHook(Thread(() => {
      server.stop()
      cachedHandlerExecutor.foreach(_.shutdown())
      executionContext.shutdown()
    }))
  }

  /** Best-effort: opens the app in the system's default browser so people don't have to know the URL or port. Falls
    * back to printing the URL when there's no desktop browse support (headless boxes, some Linux setups without
    * xdg-open configured).
    */
  private def openBrowser(): Unit = {
    val host = NetworkUtils.localNetworkAddress.getOrElse("localhost")
    val url  = s"${appConfig.scheme}://$host:${appConfig.port}/"
    try
      if Desktop.isDesktopSupported && Desktop.getDesktop.isSupported(Desktop.Action.BROWSE) then
        Desktop.getDesktop.browse(URI(url))
      else
        println(s"[StopMotionLabServer] Open $url in a browser to use the app.")
    catch {
      case e: Exception =>
        println(s"[StopMotionLabServer] Couldn't auto-open a browser ($e); open $url manually.")
    }
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
