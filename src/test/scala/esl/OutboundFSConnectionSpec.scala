package esl

import akka.actor.ActorSystem
import akka.stream.QueueOfferResult
import akka.testkit.TestKit

class OutboundFSConnectionSpec extends TestKit(ActorSystem("esl-test"))
  with EslTestKit {

  "connect function" should {
    "enqueue connect command" in {
      val outbound = new OutboundFSConnection()
      whenReady(outbound.connect()) {
        result =>
          result shouldBe QueueOfferResult.Enqueued
      }
    }
  }
}
