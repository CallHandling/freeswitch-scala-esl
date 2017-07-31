
import akka.actor.ActorSystem
import akka.stream.scaladsl.{GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.testkit.TestKit
import akka.util.ByteString
import esl.FSConnection
import esl.domain.FSMessage
import esl.parser.TestMessages

import scala.concurrent.ExecutionContext

class FSConnectionSpec extends TestKit(ActorSystem("fs-connection"))
  with EslTestKit {
  implicit val _system: ActorSystem = system

  trait FSConnectionFixture {
    implicit val ec: ExecutionContext = system.dispatcher
    val connection = new FSConnection {
      override implicit protected val system: ActorSystem = _system
      override implicit protected val materializer: ActorMaterializer = actorMaterializer
    }
  }

  "handler function should handle downstream and upstream flow" in new FSConnectionFixture {
    val (downStream, upStream) = RunnableGraph.fromGraph(GraphDSL.create(Sink.head[ByteString], Sink.head[List[FSMessage]])(Keep.both) {
      implicit b =>
        (st, sb) =>
          import GraphDSL.Implicits._
          val (source, bidiFlow) = connection.handler()
          val flow = b.add(bidiFlow)
          //downstream
          source ~> flow.in2
          flow.out2 ~> st
          //upstream
          flow.in1 <~ Source.single(ByteString(TestMessages.setVarPrivateCommand))
          sb <~ flow.out1
          ClosedShape
    }).run()

    whenReady(upStream) {
      fsMessages =>
        fsMessages should not be empty
    }
  }
}

