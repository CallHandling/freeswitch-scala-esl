import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import org.scalatest.concurrent.ScalaFutures

trait EslTestKit extends ScalaFutures
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {
  testKit: TestKit =>
  implicit val actorMaterializer = ActorMaterializer()
}
