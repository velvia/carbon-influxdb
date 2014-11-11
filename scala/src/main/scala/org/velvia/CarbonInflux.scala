package org.velvia

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.dispatch.RequiresMessageQueue
import akka.dispatch.BoundedMessageQueueSemantics
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.slf4j.Logging
import java.net.ServerSocket
import scala.concurrent.duration._
import scala.io.Source

case class CarbonLine(line: String, hostName: String)
case object FlushToInflux
case object LogMetrics

object ReportingActor {
  def props(config: Config): Props = Props(new ReportingActor(config))
}

class ReportingActor(config: Config) extends Actor
with RequiresMessageQueue[BoundedMessageQueueSemantics] {
  val reporter = new InfluxReporter(config)
  def receive = {
    case CarbonLine(line, host) => reporter.addPointFromCarbonLine(line, host)
    case FlushToInflux          => reporter.flushToInflux()
    case LogMetrics             => reporter.logMetrics()
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
  system.scheduler.schedule(30 seconds, 30 seconds, reportingActor, LogMetrics)

  // Spin up CarbonTee actor if so configured
  val teeActor: Option[ActorRef] =
    if (config.getBoolean("carbon-tee")) Some(system.actorOf(CarbonTee.props(config)))
    else                                 None

  while (true) {
    withCarbonStream(server) { (lines, host) =>
      lines.foreach { line =>
        reportingActor ! CarbonLine(line, host)
        teeActor.foreach(_ ! line)
      }
    }
  }

  // Blocks while waiting for the next connection
  private def withCarbonStream(server: ServerSocket)(f: (Iterator[String], String) => Unit) {
    val socket = server.accept()
    val fromAddr = socket.getInetAddress()
    logger.debug("Connection from {}", fromAddr)
    try {
      f(Source.fromInputStream(socket.getInputStream, "UTF8").getLines, fromAddr.getHostName())
    } finally {
      socket.close()
    }
  }
}