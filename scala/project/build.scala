import sbt._
import Keys._
import sbtassembly.Plugin._
import AssemblyKeys._

object CarbonInfluxBuild extends Build {
  val Organization = "org.velvia"
  val Name = "carbon-influxdb"
  val Version = "0.1.0-SNAPSHOT"
  val ScalaVersion = "2.11.2"

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
        "com.typesafe" % "config" % "1.2.0",
        "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
        "com.typesafe.akka" %% "akka-actor" % "2.3.6",
        "com.rojoma" %% "rojoma-json-v3" % "3.2.0",
        "org.scalaj" %% "scalaj-http" % "0.3.16",
        "ch.qos.logback" % "logback-classic" % "1.1.2",

        "org.scalatest" %% "scalatest" % "2.2.0" % "test"
      ),
      assemblyOption in assembly ~= { _.copy(prependShellScript = Some(defaultShellScript)) },
      jarName in assembly := { s"${name.value}-${version.value}" }
    )
  )
}

