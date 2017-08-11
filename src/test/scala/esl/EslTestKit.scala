package esl

import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

trait EslTestKit extends ScalaFutures
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with OptionValues
  with Inside
  with Inspectors {
  testKit: TestKit =>
  implicit val actorMaterializer = ActorMaterializer()
}
