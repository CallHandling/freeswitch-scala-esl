import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.InboundServer
import esl.domain.FSMessage
import esl.parser.DefaultParser
import scala.concurrent.duration._


object InboundTest extends App {
  implicit val system = ActorSystem()
  implicit val mater = ActorMaterializer()
  implicit val ec = system.dispatcher

  InboundServer("localhost", 8021, DefaultParser).connect("ClueCon", 2 seconds) {
    fsConnection =>
      fsConnection.map {
        f =>
          println("Client authenticate successfully")
      }
      Sink.foreach[List[FSMessage]](f => println(f))
  }.onComplete {
    f => println(f)
  }
}