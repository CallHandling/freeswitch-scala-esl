import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.OutboundServer
import esl.domain.FSMessage
import esl.parser.DefaultParser

import scala.concurrent.duration.{Duration, SECONDS}

/**
  * Created by abdhesh on 28/06/17.
  */


object OutboundTest extends App {

  implicit val system = ActorSystem("esl-system")
  implicit val actorMaterializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  implicit val timeout = Duration(2, SECONDS)

  OutboundServer("localhost", 8080, DefaultParser).startWith(
    fsConnection => {
      /*fsConnection.subscribeEvents(ChannelAnswer)(Plain)
      //fsConnection.play("<play-file-path>")
      /* Sink.foreach[List[String]] { fsMessages =>
         println(fsMessages)
       }*/
      fsConnection.where(_.eventName == Some(ChannelAnswer)) {
        eventMessage =>
          //play file with UUID
          fsConnection.play("<play-file-path>", ApplicationCommandConfig(uuid = eventMessage.uuid.getOrElse("")))
      }*/
      Sink.foreach[List[FSMessage]] { fsMessages =>
        println(fsMessages)
      }
    },
    incomingFlow => {
      incomingFlow.map { freeSwitchMessages =>
        //Convert list of free switch messages into another type of messages
        freeSwitchMessages //.map(_.contentType)
      }
    },
    result => result onComplete {
      case resultTry => println(s"Connection is closed ${resultTry}")
    }
  )
}
