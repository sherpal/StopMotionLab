package be.doeraene.utils

import java.nio.file.{Path, Paths}

/** Where persistent data (the sqlite db, uploaded media, the TLS certificate authority) lives on disk.
  *
  * In dev this is just "./data" relative to wherever sbt/the IDE happens to run from -- the usual project-root relative
  * path.
  *
  * In prod, we can't rely on the working directory at all: a `java -jar` invocation depends on the caller's shell, and
  * a jpackage app-image launched by double-clicking it doesn't reliably set the cwd to the app's own folder either. Two
  * runs (or two people on the same laptop) could then silently write their data to two different places. Instead we
  * resolve "data" next to wherever the running jar physically lives, so "next to the app" always means the same thing
  * no matter how it was launched.
  */
object AppPaths {

  def dataDir(isProd: Boolean): Path =
    if isProd then jarDirectory.resolve("data") else Paths.get("./data")

  private def jarDirectory: Path = {
    val location = getClass.getProtectionDomain.getCodeSource.getLocation.toURI
    Paths.get(location).getParent
  }

}
