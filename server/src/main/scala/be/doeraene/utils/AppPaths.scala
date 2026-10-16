package be.doeraene.utils

import java.nio.file.{Path, Paths}

/** Where persistent data (the sqlite db, uploaded media, the TLS certificate authority) lives on disk.
  *
  * In dev this is just "./data" relative to wherever sbt/the IDE happens to run from -- the usual project-root relative
  * path.
  *
  * In prod, we can't rely on the working directory (a `java -jar` invocation depends on the caller's shell, and a
  * double-clicked jpackage app-image doesn't reliably get the app's own folder as cwd), and we don't want the data to
  * live inside the app folder either: installing a new release would then start from an empty database, and deleting
  * the old app would delete the user's movies. Instead the data goes in the conventional per-user application data
  * directory of the OS, which survives upgrades and is the same no matter how the app was launched:
  *   - macOS: `~/Library/Application Support/StopMotionLab`
  *   - Windows: `%APPDATA%\StopMotionLab`
  *   - Linux: `$XDG_DATA_HOME/StopMotionLab`, or `~/.local/share/StopMotionLab`
  *
  * Can be overridden with `-DdataDir=<path>` (e.g. to keep a portable copy on a USB stick).
  */
object AppPaths {

  private val appName = "StopMotionLab"

  def dataDir(isProd: Boolean): Path =
    if isProd then sys.props.get("dataDir").map(Paths.get(_)).getOrElse(perUserDataDir) else Paths.get("./data")

  private def perUserDataDir: Path = {
    val osName = sys.props.getOrElse("os.name", "").toLowerCase
    val home   = Paths.get(sys.props("user.home"))

    if osName.contains("mac") then home.resolve("Library").resolve("Application Support").resolve(appName)
    else if osName.contains("windows") then
      sys.env.get("APPDATA").filter(_.nonEmpty).map(Paths.get(_)).getOrElse(home.resolve("AppData").resolve("Roaming")).resolve(appName)
    else
      sys.env
        .get("XDG_DATA_HOME")
        .filter(_.nonEmpty)
        .map(Paths.get(_))
        .getOrElse(home.resolve(".local").resolve("share"))
        .resolve(appName)
  }

}
