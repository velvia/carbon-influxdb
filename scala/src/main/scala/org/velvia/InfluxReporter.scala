package org.velvia

import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import com.rojoma.json.v3.ast.JValue
import com.rojoma.json.v3.codec.JsonEncode
import com.rojoma.json.v3.interpolation._
import com.rojoma.json.v3.io.CompactJsonWriter
import scala.collection.mutable
import scalaj.http.{Http, HttpOptions}

// timestamp Epoch in milliseconds
case class DataPoint(timestamp: Long, value: Double, host: String)

case class Stats(var metrics: Int, var points: Int, var posts: Int, var tries: Int) {
  def reset() { metrics = 0; points = 0;  posts = 0; tries = 0 }
}

object InfluxReporter {
  // Must align perfectly with DataPoint names
  val Columns = Seq("time", "value", "hostname")

  implicit object DataPointEncoder extends JsonEncode[DataPoint] {
    def encode(p: DataPoint) = j"""[${p.timestamp}, ${p.value}, ${p.host}]"""
  }
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
 *   connect-timeout = 500ms
 *   read-timeout = 3000ms
 * }}}
 */
class InfluxReporter(config: Config) extends StrictLogging {
  import InfluxReporter._

  val hostPort = config.getString("influx.host") + ":" + config.getInt("influx.port")
  val database = config.getString("influx.database")
  val username = config.getString("influx.username")
  val password = config.getString("influx.password")
  val connectTimeoutMs = config.getMilliseconds("connect-timeout").toInt
  val readTimeoutMs = config.getMilliseconds("read-timeout").toInt
  logger.info("Created reporter for InfluxDB {} / {}", hostPort, database)

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
      j"""{"name": $metric, "columns": $Columns, "points": $points}"""
    }
    stats.metrics += series.size
    retryInfluxPost(series.toSeq)

    pointsMap.clear()
  }

  def logMetrics() {
    logger.info(stats.toString)
    stats.reset()
  }

  private def retryInfluxPost(series: Seq[JValue], times: Int = 3) {
    logger.debug("Writing {} series to Influx...", series.size.toString)
    stats.posts += 1
    (1 to times).foreach { nTry =>
      stats.tries += 1
      writeSeries(series) match {
        case None            => return
        case Some(errString) => logger.warn(" Try {}: Error from Influx: {}", nTry.toString, errString)
      }
    }
    logger.error("Giving up, metrics will be lost.")
  }

  private def writeSeries(series: Seq[JValue]): Option[String] = {
    val data = CompactJsonWriter.toString(j"$series")
    try {
      val (status, _, body) = Http.postData(s"http://$hostPort/db/$database/series", data).
                                header("content-type", "application/json").
                                option(HttpOptions.connTimeout(connectTimeoutMs)).
                                option(HttpOptions.readTimeout(readTimeoutMs)).
                                auth(username, password).
                                asHeadersAndParse(Http.readString)
      if (status >= 200 && status < 300) return None
      Some(body)
    } catch {
      case e: Exception => Some(s"${e.getClass.getName}: ${e.getMessage}")
    }
  }
}