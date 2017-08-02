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
      /**Inbound fsConnection future will get complete when client is authorised by freeswitch*/
      fsConnection.onComplete {
        case Success(conn) =>
          conn.subscribeEvents(EventNames.All).foreach {
            _ =>
              conn.play("/usr/share/freeswitch/sounds/en/us/callie/conference/8000/conf-pin.wav").foreach {
                commandResponse =>
                  /**This future will get complete, when FS send command/reply message to the socket*/
                  commandResponse.commandReply.foreach(f => logger.info(s"Got command reply: ${f}"))

                  /**This future will get complete, when FS send CHANNEL_EXECUTE event to the socket*/
                  commandResponse.executeEvent.foreach(f => logger.info(s"Got ChannelExecute event: ${f}"))

                  /**This future will get complete, when FS send CHANNEL_EXECUTE_COMPLETE  event to the socket*/
                  commandResponse.executeComplete.foreach(f => logger.info(s"ChannelExecuteComplete event: ${f}"))
              }
          }
        case Failure(ex) => logger.info("failed to make inbound socket connection", ex)
      }
      Sink.foreach[List[FSMessage]] { fsMessages => logger.info(fsMessages) }
  }.onComplete {
    case Success(result) => logger.info(s"Stream completed with result ${result}")
    case Failure(ex) => logger.info(s"Stream failed with exception ${ex}")
  }
}