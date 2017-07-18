import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.OutboundServer
import esl.domain.FSMessage
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Future
import scala.concurrent.duration.{Duration, SECONDS}
import scala.util.{Failure, Success}

/**
  * Created by abdhesh on 28/06/17.
  */


object OutboundTest extends App with Logging {

  implicit val system = ActorSystem("esl-system")
  implicit val actorMaterializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  implicit val timeout = Duration(2, SECONDS)

  OutboundServer("127.0.0.1", 8084).startWith(
    fsConnection => {
      fsConnection.onComplete {
        case Success(conn) =>
          //conn.subscribeEvents(ChannelState)
          conn.play("/usr/share/freeswitch/sounds/en/us/callie/voicemail/8000/vm-tutorial_change_pin.wav").map {
            reply =>
              println("::::::::::" + reply)
          }
        case Failure(ex) => logger.info("failed to make connection", ex)
      }
      Sink.foreach[List[FSMessage]] { fsMessages => logger.info(fsMessages) }
    },
    result => result onComplete {
      case resultTry => logger.info(s"Connection is closed ${resultTry}")
    }
  )
}
