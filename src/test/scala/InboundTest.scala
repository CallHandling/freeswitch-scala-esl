import OutboundTest.logger
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.InboundServer
import esl.domain.{EventNames, FSMessage}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._
import scala.util.{Failure, Success}


object InboundTest extends App with Logging {
  implicit val system = ActorSystem()
  implicit val mater = ActorMaterializer()
  implicit val ec = system.dispatcher
  implicit val timeout = Duration(5, SECONDS)
  InboundServer("localhost", 8021).connect("ClueCon") {
    fsConnection =>
      fsConnection.onComplete {
        case Success(conn) =>
          conn.subscribeEvents(EventNames.All).foreach {
            _ =>
              conn.play("/usr/share/freeswitch/sounds/en/us/callie/conference/8000/conf-pin.wav").foreach {
                commandResponse =>
                  commandResponse.commandReply.foreach(f => logger.info(s"Got command reply: ${f}"))
                  commandResponse.executeEvent.foreach(f => logger.info(s"Got ChannelExecute event: ${f}"))
                  commandResponse.executeComplete.foreach(f => logger.info(s"ChannelExecuteComplete event: ${f}"))
              }
          }
        case Failure(ex) => logger.info("failed to make inbound socket connection", ex)
      }
      Sink.foreach[List[FSMessage]] { fsMessages => logger.info(fsMessages) }
  }.onComplete {
    case result => logger.info(s"Stream completed with result ${result}")
  }
}