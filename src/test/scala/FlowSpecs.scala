
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ActorMaterializer, Attributes, OverflowStrategy}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

class FlowSpecs extends TestKit(ActorSystem("MySpec"))
  with ScalaFutures
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll {

  final val Debug = false
  implicit val materializer = ActorMaterializer()

  def graph(async: Boolean) =
    Source.unfold(1)(x ⇒ Some(x → x)).filter(_ % 2 == 1)
      .alsoTo(Flow[Int].fold(0)(_ + _).to(Sink.head.named("otherSink")).addAttributes(if (async) Attributes.asyncBoundary else Attributes.none))
      .via(Flow[Int].fold(1)(_ + _).named("mainSink"))

  "SubFusingActorMaterializer" must {

    "work with asynchronous boundaries in the subflows" in {
      val async = Flow[Int].map(_ * 2).async
      Source(0 to 9)
        .map(_ * 10)
        .flatMapMerge(5, i ⇒ Source(i to (i + 9)).via(async))
        .grouped(1000)
        .runWith(Sink.head)
        .futureValue
        .sorted should ===(0 to 198 by 2)
    }

  }

  "TestProbe" must {
    "receive expected messages" in {
      import system.dispatcher
      import akka.pattern.pipe

      val sourceUnderTest = Source(1 to 4).grouped(2)

      val probe = TestProbe()
      sourceUnderTest.runWith(Sink.seq).pipeTo(probe.ref)
      probe.expectMsg(3.seconds, Seq(Seq(1, 2), Seq(3, 4)))
    }
  }

  "TestProbe as Sink" must {
    "get messages" in {
      case object Tick
      val sourceUnderTest = Source.tick(0.seconds, 200.millis, Tick)

      val probe = TestProbe()
      val cancellable = sourceUnderTest.to(Sink.actorRef(probe.ref, "completed")).run()

      probe.expectMsg(1.second, Tick)
      probe.expectNoMsg(100.millis)
      probe.expectMsg(3.seconds, Tick)
      cancellable.cancel()
      probe.expectMsg(3.seconds, "completed")
    }
  }

  "Source.actorRef" in {
    val sinkUnderTest = Flow[Int].map(_.toString).toMat(Sink.fold("")(_ + _))(Keep.right)

    val (ref, future) = Source.actorRef(8, OverflowStrategy.fail)
      .toMat(sinkUnderTest)(Keep.both).run()

    ref ! 1
    ref ! 2
    ref ! 3
    ref ! akka.actor.Status.Success("done")

    val result = Await.result(future, 3.seconds)
    assert(result == "123")
  }

  "Streams TestKit's TestSink.probe" in {
    val sourceUnderTest = Source(1 to 4).filter(_ % 2 == 0).map(_ * 2)

    sourceUnderTest
      .runWith(TestSink.probe[Int])
      .request(2)
      .expectNext(4, 8)
      .expectComplete()
  }
}

