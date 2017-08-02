import akka.actor.ActorSystem
import akka.testkit.TestKit
import esl.InboundFSConnection

class InboundFSConnectionSpec extends TestKit(ActorSystem("outbound-connection"))
  with EslTestKit {

  "connect function" should {
    "enqueue Auth command" in {
      val inbound = new InboundFSConnection()
      whenReady(inbound.connect("ClueCon")) {
        commandResponse =>
          commandResponse.command.toString shouldBe "auth ClueCon\n\n"
      }
    }
  }

}
