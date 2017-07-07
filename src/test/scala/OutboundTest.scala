import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.OutboundServer
import esl.parser.DefaultParser

/**
  * Created by abdhesh on 28/06/17.
  */


object OutboundTest extends App {

  implicit val system = ActorSystem("esl-system")
  implicit val actorMaterializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  OutboundServer("localhost", 8080, DefaultParser).startWith(
    fsConnection => {
      fsConnection.play("<play-file-path>")
      Sink.foreach[List[String]](println)
    },
    incomingFlow => {
      incomingFlow.map { freeSwitchMessages =>
        //Convert list of free switch messages into another type of messages
        freeSwitchMessages.map(_.contentType)
      }
    },
    result => result onComplete {
      case resultTry => println(s"Connection is closed ${resultTry}")
    }
  )
}
