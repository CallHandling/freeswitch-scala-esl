package esl

import akka.actor.ActorSystem
import akka.event.{Logging, MarkerLoggingAdapter}
import akka.stream.QueueOfferResult
import akka.testkit.TestKit

class OutboundFSConnectionSpec
    extends TestKit(ActorSystem("esl-test"))
    with EslTestKit {
  implicit val adapter: MarkerLoggingAdapter =
    Logging.withMarker(system, "hubbub-esl-fs")
  "connect function" should {
    "enqueue connect command" in {
      val outbound = new OutboundFSConnection(enableDebugLogs = true)
      whenReady(outbound.connect()) { result =>
        result shouldBe QueueOfferResult.Enqueued
      }
    }
  }
}
