package org.velvia

import akka.actor.{Actor, ActorSystem, Props}
import akka.dispatch.RequiresMessageQueue
import akka.dispatch.BoundedMessageQueueSemantics
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.slf4j.Logging
import java.net.ServerSocket
import scala.concurrent.duration._
import scala.io.Source

case class CarbonLine(line: String)
case object FlushToInflux

object ReportingActor {
  def props(config: Config): Props = Props(new ReportingActor(config))
}

class ReportingActor(config: Config) extends Actor
with RequiresMessageQueue[BoundedMessageQueueSemantics] {
  val reporter = new InfluxReporter(config)
  def receive = {
    case CarbonLine(line) => reporter.addPointFromCarbonLine(line)
    case FlushToInflux    => reporter.flushToInflux()
  }
}

/**
 * This is the main CarbonInflux app, which takes a socket connection with plaintext Carbon
 * Graphite input, batches up the metrics, and sends them to InfluxDB.
 *
 * See reference.conf for defaults.
 *
 * It takes one argument, the path to a config file, which is mandatory for configuring
 * the Influx instance to write to.
 */
object CarbonInflux extends App with Logging {
  if (args.length != 1) {
    println("This app takes one arg, the path to a config file.")
    sys.exit(0)
  }

  val system = ActorSystem("CarbonInflux")
  import system.dispatcher

  val config = ConfigFactory.parseFile(new java.io.File(args(0))).
                 withFallback(ConfigFactory.defaultReference).
                 getConfig("carbon-influx")
  val server = new ServerSocket(config.getInt("carbon-port"))
  logger.info("Started socket on port {}", server.getLocalPort.toString)

  // Spin up reporter actor and send it regular flush messages
  val reportingActor = system.actorOf(ReportingActor.props(config), "reporter")
  val flushIntervalMs = config.getMilliseconds("influx-send-interval").toInt
  system.scheduler.schedule(flushIntervalMs milliseconds, flushIntervalMs milliseconds,
                            reportingActor, FlushToInflux)

  while (true) {
    for { line <- getCarbonStream(server) } {
      reportingActor ! CarbonLine(line)
    }
    logger.info("---- end connection ---")
  }

  // Blocks while waiting for the next connection
  private def getCarbonStream(server: ServerSocket): Iterator[String] = {
    val socket = server.accept()
    Source.fromInputStream(socket.getInputStream, "UTF8").getLines
  }
}