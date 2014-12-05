package org.velvia

import akka.actor.{Actor, Props}
import akka.dispatch.RequiresMessageQueue
import akka.dispatch.BoundedMessageQueueSemantics
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import java.io.PrintWriter

object CarbonTee {
    def props(config: Config): Props = Props(new CarbonTee(config))
}

/**
 * An actor to tee incoming lines to another socket.
 * It's designed to relay Carbon lines, but technically I guess it tees any text line input source
 * to another socket expecting text lines.
 *
 * Note that PrintWriter doesn't throw exceptions, so we need to check if something went wrong
 * explicitly.  We handle that by throwing our own exception, and the default Actor supervision
 * will restart this actor, which sets up a new socket.
 */
class CarbonTee(config: Config) extends Actor with StrictLogging
with RequiresMessageQueue[BoundedMessageQueueSemantics] {
  val teeCarbonHost = config.getString("tee-carbon-host")
  val teeCarbonPort = config.getInt("tee-carbon-port")
  val teeSocket = new java.net.Socket(teeCarbonHost, teeCarbonPort)
  val writer = new PrintWriter(teeSocket.getOutputStream)

  logger.info("Teeing carbon input to {}:{}", teeCarbonHost, teeCarbonPort.toString)

  // override actor stop logic to close the socket
  override def postStop() {
    writer.close()
    teeSocket.close()
    super.postStop()
  }

  def receive = {
    case line: String =>
      writer.println(line)
      if (writer.checkError()) {
        logger.error("Error writing to tee socket {}:{}, restarting", teeCarbonHost, teeCarbonPort.toString)
        throw new RuntimeException("Error writing to tee socket")
      }
  }
}