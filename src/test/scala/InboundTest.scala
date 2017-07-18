import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.InboundServer
import esl.domain.FSMessage

import scala.concurrent.duration._


object InboundTest extends App {
  implicit val system = ActorSystem()
  implicit val mater = ActorMaterializer()
  implicit val ec = system.dispatcher
  implicit val timeout = Duration(5, SECONDS)
  InboundServer("localhost", 8021).connect("ClueCon") {
    fsConnection =>
      fsConnection.onComplete {
        f =>
          println("Client authenticate successfully"+f)
        //f.subscribeEvents(EventNames.All)
      }
      Sink.foreach[List[FSMessage]] {
        fsMessages =>
        // fs
      }
  }.onComplete {
    f => println(":::"+f)
  }
}