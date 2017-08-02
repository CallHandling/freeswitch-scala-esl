import akka.actor.ActorSystem
import akka.testkit.TestKit
import esl.OutboundFSConnection

class OutboundFSConnectionSpec extends TestKit(ActorSystem("outbound-connection"))
  with EslTestKit {

  "connect function" should {
    "enqueue connect command" in {
      val outbound = new OutboundFSConnection()
      whenReady(outbound.connect()) {
        commandResponse =>
          commandResponse.command.toString shouldBe "connect\n\n"
      }
    }
  }
}
