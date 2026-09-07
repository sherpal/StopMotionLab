import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSUseMainModuleInitializer

import java.nio.charset.StandardCharsets

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
    "-language:implicitConversions"
  ),
  libraryDependencies ++= Seq("org.scalameta" %% "munit" % "1.0.4" % Test)
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

val flywayVersion = "13.3.0"

def databaseStuff = Seq(
  "com.lihaoyi" %% "scalasql-simple"           % "0.3.1",
  "org.flywaydb" % "flyway-core"               % flywayVersion,
  "org.flywaydb" % "flyway-database-nc-sqlite" % flywayVersion,
  "org.xerial"   % "sqlite-jdbc"               % "3.53.2.1"
)

lazy val server = project
  .in(file("./server"))
  .settings(
    commonSettings,
    name := "StopMotionLabServer",
    libraryDependencies ++= Seq(
      "com.lihaoyi"      %% "cask"   % "0.11.3",
      "com.lihaoyi"      %% "os-lib" % "0.11.8",
      "com.google.zxing" % "core"    % "3.5.3"
    ) ++ databaseStuff,
    fork := true
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

    esModule
  )
  .dependsOn(common.js(commonScalaVersion))
