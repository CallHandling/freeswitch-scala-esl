import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.FSConnection.FSData
import esl.{FSSocket, OutboundFSConnection, OutboundServer}
import esl.domain._
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Future
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
                  Thread.sleep(2000)
println(commandResponse.commandReply)
                  /** This future will get complete, when FS send command/reply message to the socket */
                  commandResponse.commandReply.foreach(f => logger.info(s"Got command reply: "))

                  /** This future will get complete, when FS send CHANNEL_EXECUTE event to the socket */
                  commandResponse.executeEvent.foreach(f => logger.info(s"Got ChannelExecute event:"))

                  /** This future will get complete, when FS send CHANNEL_EXECUTE_COMPLETE  event to the socket */
                  commandResponse.executeComplete.foreach(f => logger.info(s"Got ChannelExecuteComplete event:"))
              }
          }
        case Failure(ex) => logger.info("failed to make outbound socket connection", ex)
      }
      //Sink.actorRef[FSData](system.actorOf(MyActor.props(fsSocket)), "")
      Sink.foreach[FSData] { fsData =>
        //logger.info(s"Fs Messages: ${fsData.fsMessages}")
      }
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

class MyActor(socket: Future[FSSocket[OutboundFSConnection]]) extends Actor {

  //Here you can access fs connection
  override def receive: Receive = {
    case fsData: FSData =>
    //fsData.fSConnection
    //fsData.fsMessages
  }
}

object MyActor {
  def props(socket: Future[FSSocket[OutboundFSConnection]]) = Props(new MyActor(socket))
}