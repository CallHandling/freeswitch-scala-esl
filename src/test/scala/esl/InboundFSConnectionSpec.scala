package esl

import akka.actor.ActorSystem
import akka.event.{Logging, MarkerLoggingAdapter}
import akka.stream.QueueOfferResult
import akka.testkit.TestKit

class InboundFSConnectionSpec extends TestKit(ActorSystem("esl-test"))
  with EslTestKit {
  implicit val adapter: MarkerLoggingAdapter = Logging.withMarker(system, "hubbub-esl-fs")
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
