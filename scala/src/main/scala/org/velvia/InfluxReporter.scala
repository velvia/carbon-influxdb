package org.velvia

import com.typesafe.config.Config
import com.typesafe.scalalogging.slf4j.Logging
import org.influxdb.{Client, Series}
import scala.collection.mutable

case class DataPoint(timestamp: Long, value: Double)  // timestamp Epoch in milliseconds

object InfluxReporter {
  // Must align perfectly with DataPoint names
  val Columns = Array("time", "value")
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

  def addPoint(metricName: String, point: DataPoint) {
    val points = pointsMap.getOrElseUpdate(metricName, Seq.empty[DataPoint])
    pointsMap(metricName) = points :+ point
  }

  /**
   * Adds a point from a line of Carbon plaintext input
   * @param carbonLine a line of the form "<metricName> <value> <timestamp>"
   */
  def addPointFromCarbonLine(carbonLine: String) {
    val parts = carbonLine.trim.split(" ")
    if (parts.length != 3) return
      // NOTE: need to convert Carbon/Graphite seconds timestamp to Influx ms-based
    try { addPoint(parts(0), DataPoint(parts(2).toLong * 1000, parts(1).toDouble)) }
    catch { case e: Exception =>  logger.warn("Could not parse line " + carbonLine, e) }
  }

  def flushToInflux() {
    val series = pointsMap.map { case (metric, points) =>
      val pointsArray = points.map { p => Array[Any](p.timestamp, p.value) }.toArray
      Series(metric, Columns, pointsArray)
    }.toArray
    logger.info("Writing {} series to Influx...", series.size.toString)
    client.writeSeries(series.toArray)

    logger.info("Flushing pointsMap...")
    pointsMap.clear()
  }
}