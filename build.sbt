import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSUseMainModuleInitializer

import java.nio.charset.StandardCharsets
import scala.sys.process.Process

ThisBuild / version := "0.1.0-SNAPSHOT"

val commonScalaVersion = "3.8.4"

def esModule = Def.settings(scalaJSLinkerConfig ~= {
  _.withModuleKind(ModuleKind.ESModule)
})

scalaVersion := commonScalaVersion

val scalaMetadataGenerated = AttributeKey[Boolean]("scalaMetadataGenerated")

Global / onLoad := {
  val previous = (Global / onLoad).value

  state =>
    if (state.get(scalaMetadataGenerated).contains(true)) {
      previous(state)
    } else {
      // slant font
      println("""
          |   _____ __                 __  ___      __  _                __          __
          |  / ___// /_____  ____     /  |/  /___  / /_(_)___  ____     / /   ____ _/ /_
          |  \__ \/ __/ __ \/ __ \   / /|_/ / __ \/ __/ / __ \/ __ \   / /   / __ `/ __ \
          | ___/ / /_/ /_/ / /_/ /  / /  / / /_/ / /_/ / /_/ / / / /  / /___/ /_/ / /_/ /
          |/____/\__/\____/ .___/  /_/  /_/\____/\__/_/\____/_/ /_/  /_____/\__,_/_.___/
          |              /_/
          |
          |""".stripMargin)

      previous(state.put(scalaMetadataGenerated, true))
    }

}

val commonSettings = Seq(
  version := "1.0.0",
  scalacOptions ++= Seq(
    "-encoding",
    "utf8",
    "-deprecation",
    "-unchecked",
    "-language:higherKinds",
    "-feature",
    "-language:implicitConversions",
    "-Werror"
  ),
  libraryDependencies ++= Seq("org.scalameta" %% "munit" % "1.3.6" % Test)
)

val circeVersion = "0.14.9"

lazy val common = projectMatrix
  .in(file("./common"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "be.doeraene" %% "url-dsl" % "0.7.0",
      "com.lihaoyi" %% "castor"  % "0.3.2"
    ) ++ Seq( // circe for json serialisation
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion)
  )
  .jvmPlatform(scalaVersions = Seq(commonScalaVersion))
  .jsPlatform(scalaVersions = Seq(commonScalaVersion))

val flywayVersion = "13.6.0"

def databaseStuff = Seq(
  "com.lihaoyi" %% "scalasql-simple"           % "0.3.2",
  "org.flywaydb" % "flyway-core"               % flywayVersion,
  "org.flywaydb" % "flyway-database-nc-sqlite" % flywayVersion,
  "org.xerial"   % "sqlite-jdbc"               % "3.53.4.0"
)

lazy val server = project
  .in(file("./server"))
  .settings(
    commonSettings,
    name := "StopMotionLabServer",
    libraryDependencies ++= Seq(
      "com.lihaoyi"     %% "cask"           % "0.11.3",
      "com.lihaoyi"     %% "os-lib"         % "0.11.8",
      "com.google.zxing" % "core"           % "3.5.4",
      "org.bouncycastle" % "bcpkix-jdk18on" % "1.86" // mints the local self-signed TLS certificate authority/leaf certs
    ) ++ databaseStuff,
    fork := true,

    assembly / assemblyMergeStrategy := {
      case "module-info.class" =>
        MergeStrategy.discard

      case PathList("META-INF", "versions", _, "module-info.class") =>
        MergeStrategy.discard

      case PathList("META-INF", "versions", _, "OSGI-INF", "MANIFEST.MF") =>
        MergeStrategy.discard

      case x =>
        MergeStrategy.defaultMergeStrategy(x)
    }
  )
  .dependsOn(common.jvm(commonScalaVersion))

lazy val frontend = project
  .in(file("./frontend"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    commonSettings,
    name := "StopMotionLabFrontend",
    libraryDependencies ++= Seq(
      "com.raquo"   %% "laminar"            % "17.2.1",
      "be.doeraene" %% "web-components-ui5" % "2.12.2"
    ),
    scalaJSUseMainModuleInitializer := true,

    Compile / fastLinkJS := Def.uncached {
      val targetDir = baseDirectory.value / "generated" / "fastopt"
      val outputDir = (Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value

      IO.createDirectory(targetDir)

      IO.copyFile(
        outputDir / "main.js",
        targetDir / "main.js"
      )

      IO.copyFile(
        outputDir / "main.js.map",
        targetDir / "main.js.map"
      )

      val outputFile   = baseDirectory.value / "scala-metadata.js"
      val frontendName = name.value

      IO.writeLines(
        outputFile,
        s"""
           |const scalaVersion = "$commonScalaVersion"
           |const frontendName = "${frontendName.toLowerCase}"
           |
           |exports.scalaMetadata = {
           |  scalaVersion: scalaVersion,
           |  frontendName: frontendName,
           |}
           |""".stripMargin.split("\n").toList,
        StandardCharsets.UTF_8
      )

      (Compile / fastLinkJS).value
    },
    Compile / fullLinkJS := Def.uncached {
      val targetDir = baseDirectory.value / "generated" / "opt"
      val outputDir = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value

      IO.createDirectory(targetDir)

      IO.copyFile(
        outputDir / "main.js",
        targetDir / "main.js"
      )

      IO.copyFile(
        outputDir / "main.js.map",
        targetDir / "main.js.map"
      )

      val outputFile   = baseDirectory.value / "scala-metadata.js"
      val frontendName = name.value

      IO.writeLines(
        outputFile,
        s"""
           |const scalaVersion = "$commonScalaVersion"
           |const frontendName = "${frontendName.toLowerCase}"
           |
           |exports.scalaMetadata = {
           |  scalaVersion: scalaVersion,
           |  frontendName: frontendName,
           |}
           |""".stripMargin.split("\n").toList,
        StandardCharsets.UTF_8
      )

      (Compile / fullLinkJS).value
    },

    esModule
  )
  .dependsOn(common.js(commonScalaVersion))

val buildFrontend = taskKey[Unit]("Build frontend")

Global / buildFrontend := Def.uncached {
  /*
  To build the frontend, we do the following things:
  - fullLinkJS the frontend sub-module
  - run npm ci in the frontend directory (might not be required)
  - package the application with vite-js (output will be in the resources of the server sub-module)
   */
  (frontend / Compile / fullLinkJS).value
  val npmCiExit =
    Process(Utils.npm :: "ci" :: Nil, cwd = baseDirectory.value / "frontend").run().exitValue()
  if (npmCiExit > 0) {
    throw new IllegalStateException(s"npm ci failed. See above for reason")
  }

  println(s"Running npm run build in ${baseDirectory.value / "frontend"}")
  val buildExit = Process(
    Utils.npm :: "run" :: "build" :: Nil,
    cwd = baseDirectory.value / "frontend"
  ).run().exitValue()
  if (buildExit > 0) {
    throw new IllegalStateException(s"Building frontend failed. See above for reason")
  }

  IO.copyDirectory(
    baseDirectory.value / "frontend" / "dist",
    baseDirectory.value / "server" / "src" / "main" / "resources" / "static"
  )
}

(server / assembly) := (server / assembly).dependsOn(Global / buildFrontend).value

val packageApplication = taskKey[File]("Package the whole application into a fat jar")

Global / packageApplication := Def.uncached {
  /*
  To package the whole application into a fat jar, we do the following things:
  - call sbt assembly to make the fat jar for us (config in the server sub-module settings)
  - we move it to the ./dist folder so that the Dockerfile can be independent of Scala versions and other details
   */
  val fatJar = fileConverter.value.toPath((server / assembly).value).toFile
  println(s"Fat har is $fatJar")
  val target = baseDirectory.value / "dist" / "app.jar"
  IO.copyFile(fatJar, target)
  target
}
