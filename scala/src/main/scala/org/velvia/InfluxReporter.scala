package org.velvia

import com.typesafe.config.Config
import com.typesafe.scalalogging.slf4j.Logging
import org.influxdb.{Client, Series}
import scala.collection.mutable

// timestamp Epoch in milliseconds
case class DataPoint(timestamp: Long, value: Double, host: String)

case class Stats(var metrics: Int, var points: Int, var posts: Int, var tries: Int) {
  def reset() { metrics = 0; points = 0;  posts = 0; tries = 0 }
}

object InfluxReporter {
  // Must align perfectly with DataPoint names
  val Columns = Array("time", "value", "hostname")
}

/**
 * Tracks points on metrics, and reports them to influxDB.
 * Configuration:
 * {{{
 *   influx {
 *     host = "localhost"
 *     port = 8086
 *     database = Foo
 *     username = ###
 *     password = ###
 *   }
 * }}}
 */
class InfluxReporter(config: Config) extends Logging {
  import InfluxReporter._

  val hostPort = config.getString("influx.host") + ":" + config.getInt("influx.port")
  val client = new Client(host = hostPort,
                          database = config.getString("influx.database"),
                          username = config.getString("influx.username"),
                          password = config.getString("influx.password"))
  logger.info("Created client for InfluxDB {} / {}", hostPort, client.database)

  val pointsMap = mutable.HashMap[String, Seq[DataPoint]]()
  val stats = Stats(0, 0, 0, 0)

  def addPoint(metricName: String, point: DataPoint) {
    val points = pointsMap.getOrElseUpdate(metricName, Seq.empty[DataPoint])
    pointsMap(metricName) = points :+ point
    stats.points += 1
  }

  /**
   * Adds a point from a line of Carbon plaintext input
   * @param carbonLine a line of the form "<metricName> <value> <timestamp>"
   */
  def addPointFromCarbonLine(carbonLine: String, hostName: String) {
    val parts = carbonLine.trim.split(" ")
    if (parts.length != 3) return
      // NOTE: need to convert Carbon/Graphite seconds timestamp to Influx ms-based
    try { addPoint(parts(0), DataPoint(parts(2).toLong * 1000, parts(1).toDouble, hostName)) }
    catch { case e: Exception =>  logger.warn("Could not parse line " + carbonLine, e) }
  }

  def flushToInflux() {
    val series = pointsMap.map { case (metric, points) =>
      val pointsArray = points.map { p => Array[Any](p.timestamp, p.value, p.host) }.toArray
      Series(metric, Columns, pointsArray)
    }.toArray
    stats.metrics += series.size
    retryInfluxPost(series)

    pointsMap.clear()
  }

  def logMetrics() {
    logger.info(stats.toString)
    stats.reset()
  }

  private def retryInfluxPost(series: Array[Series], times: Int = 3) {
    logger.debug("Writing {} series to Influx...", series.size.toString)
    stats.posts += 1
    (1 to times).foreach { nTry =>
      stats.tries += 1
      client.writeSeries(series.toArray) match {
        case None            => return
        case Some(errString) => logger.warn(" Try {}: Error from Influx: {}", nTry.toString, errString)
      }
    }
    logger.error("Giving up, metrics will be lost.")
  }
}