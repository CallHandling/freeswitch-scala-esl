import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.InboundServer
import esl.domain.EventNames.All
import esl.domain.FSMessage
import org.apache.logging.log4j.scala.Logging

import scala.util.{Failure, Success}


object InboundTest extends App with Logging {
  implicit val system = ActorSystem()
  implicit val mater = ActorMaterializer()
  implicit val ec = system.dispatcher
  InboundServer("localhost", 8021).connect("ClueCon") {
    fSSocket =>
      /**Inbound fsConnection future will get complete when client is authorised by freeswitch*/
      fSSocket.onComplete {
        case Success(socket) =>
          socket.fsConnection.subscribeEvents(All).foreach {
            _ =>
              socket.fsConnection.play("/usr/share/freeswitch/sounds/en/us/callie/conference/8000/conf-pin.wav").foreach {
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
    case Success(result) => logger.info(s"Server started successfully with result ${result}")
    case Failure(ex) => logger.info(s"Server failed with exception ${ex}")
  }
}