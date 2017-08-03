import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.OutboundServer
import esl.domain._
import org.apache.logging.log4j.scala.Logging

import scala.util.{Failure, Success}

/**
  * Created by abdhesh on 28/06/17.
  */


object OutboundTest extends App with Logging {

  implicit val system = ActorSystem("esl-system")
  implicit val actorMaterializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  OutboundServer("127.0.0.1", 8084).startWith(
    fsSocket => {
      /** Outbound fsConnection future will get complete when `connect` command get response from freeswitch */
      fsSocket.onComplete {
        case Success(socket) =>
          val uuid = socket.reply.headers.get(HeaderNames.uniqueId).getOrElse("")
          socket.fsConnection.subscribeMyEvents(uuid).foreach {
            _ =>
              socket.fsConnection.play("/usr/share/freeswitch/sounds/en/us/callie/conference/8000/conf-pin.wav").foreach {
                commandResponse =>

                  /** This future will get complete, when FS send command/reply message to the socket */
                  commandResponse.commandReply.foreach(f => logger.info(s"Got command reply: ${f}"))

                  /** This future will get complete, when FS send CHANNEL_EXECUTE event to the socket */
                  commandResponse.executeEvent.foreach(f => logger.info(s"Got ChannelExecute event: ${f}"))

                  /** This future will get complete, when FS send CHANNEL_EXECUTE_COMPLETE  event to the socket */
                  commandResponse.executeComplete.foreach(f => logger.info(s"ChannelExecuteComplete event: ${f}"))
              }
          }
        case Failure(ex) => logger.info("failed to make outbound socket connection", ex)
      }
      Sink.foreach[List[FSMessage]] { fsMessages => logger.info(fsMessages) }
    },
    result => result onComplete {
      case Success(conn) => logger.info(s"Connection has closed successfully ${conn.localAddress}")
      case Failure(ex) => logger.info(s"Failed to close connection: ${ex}")
    }
  ).onComplete {
    case Success(result) => logger.info(s"Outbound socket started successfully with result ${result}")
    case Failure(ex) => logger.info(s"Outbound socket failed to start ${ex}")
  }
}