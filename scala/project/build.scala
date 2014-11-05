import sbt._
import Keys._
import sbtassembly.Plugin._
import AssemblyKeys._

object CarbonInfluxBuild extends Build {
  val Organization = "org.velvia"
  val Name = "carbon-influxdb"
  val Version = "0.1.0-SNAPSHOT"
  val ScalaVersion = "2.10.4"

  lazy val project = Project (
    "influxdb-scala",
    file("."),
    settings = Defaults.defaultSettings ++ assemblySettings ++ Seq(
      organization := Organization,
      name := Name,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      resolvers += "Apache repo" at "https://repository.apache.org/content/repositories/releases",
      libraryDependencies ++= Seq(
        "com.typesafe" % "config"        % "1.2.0",
        "com.typesafe" %% "scalalogging-slf4j" % "1.1.0",
        "com.typesafe.akka" %% "akka-actor" % "2.3.6",
        "org.json4s" %% "json4s-jackson" % "3.2.6",
        "com.ning" % "async-http-client" % "1.0.0" exclude("log4j", "log4j"),
        "ch.qos.logback" % "logback-classic" % "1.1.2",
        "org.slf4j"      % "log4j-over-slf4j" % "1.7.7",

        "org.scalatest" %% "scalatest" % "2.1.0" % "test"
      ),
      assemblyOption in assembly ~= { _.copy(prependShellScript = Some(defaultShellScript)) },
      jarName in assembly := { s"${name.value}-${version.value}" }
    )
  )
}

