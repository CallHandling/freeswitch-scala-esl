package esl

import akka.actor.ActorSystem
import akka.stream.QueueOfferResult
import akka.testkit.TestKit

class InboundFSConnectionSpec extends TestKit(ActorSystem("outbound-connection"))
  with EslTestKit {

  "connect function" should {
    "enqueue Auth command" in {
      val inbound = new InboundFSConnection()
      whenReady(inbound.connect("ClueCon")) {
        result =>
          result shouldBe QueueOfferResult.Enqueued
      }
    }
  }

}
