
import akka.actor.ActorSystem
import akka.stream.scaladsl.{BidiFlow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.testkit.TestKit
import akka.util.ByteString
import esl.FSConnection
import esl.domain.CallCommands.PlayFile
import esl.domain._
import esl.parser.TestMessages
import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class FSConnectionSpec extends TestKit(ActorSystem("fs-connection"))
  with EslTestKit {
  implicit val _system: ActorSystem = system

  trait FSConnectionFixture {
    implicit val ec: ExecutionContext = system.dispatcher
    val connection = new FSConnection {
      override implicit protected val system: ActorSystem = _system
      override implicit protected val materializer: ActorMaterializer = actorMaterializer
    }

    def runGraph(bidiFlow: BidiFlow[ByteString, List[FSMessage], FSCommand, ByteString, _],
                 downstreamSource: Source[FSCommand, _],
                 upstreamSource: Source[ByteString, _],
                 downstreamSink: Sink[ByteString, Future[ByteString]],
                 upstreamSink: Sink[List[FSMessage], Future[List[FSMessage]]]): (Future[ByteString], Future[List[FSMessage]]) = {
      RunnableGraph.fromGraph(GraphDSL.create(downstreamSink, upstreamSink)(Keep.both) {
        implicit b =>
          (st, sb) =>
            import GraphDSL.Implicits._
            val flow = b.add(bidiFlow)
            //downstream
            downstreamSource ~> flow.in2
            flow.out2 ~> st
            //upstream
            flow.in1 <~ upstreamSource
            sb <~ flow.out1
            ClosedShape
      }).run()
    }
  }

  "A handler function" should {
    "handle upstream flow and parse incoming data into FS messages" in new FSConnectionFixture {
      val (source, bidiFlow) = connection.handler()
      val upstreamSource = Source.single(ByteString(TestMessages.setVarPrivateCommand))
      val (downStream, upStream) = runGraph(bidiFlow, source, upstreamSource, Sink.head[ByteString], Sink.head[List[FSMessage]])
      whenReady(upStream) {
        fsMessages =>
          fsMessages should not be empty
      }
    }

    "handle downstream stream and push FS command to downstream" in new FSConnectionFixture {
      val (_, bidiFlow) = connection.handler()
      val fsCmd = PlayFile("/usr/share/freeswitch/sounds/en/us/callie/voicemail/8000/vm-tutorial_change_pin.wav",
        ApplicationCommandConfig())
      val source = Source.single(fsCmd)
      val expected = s"sendmsg \nEvent-UUID: ${fsCmd.eventUuid}\ncall-command: execute\nexecute-app-name: playback\ncontent-type: text/plain\ncontent-length: 83\n\n/usr/share/freeswitch/sounds/en/us/callie/voicemail/8000/vm-tutorial_change_pin.wav\n"
      val upstreamSource = Source.single(ByteString(TestMessages.setVarPrivateCommand))
      val (downStream, upStream) = runGraph(bidiFlow, source, upstreamSource, Sink.head[ByteString], Sink.head[List[FSMessage]])
      whenReady(downStream) {
        fsCommand =>
          fsCommand.utf8String shouldBe expected
      }
    }
  }

  "FS helper functions" should {
    "play function should generate FS playback command" in new FSConnectionFixture {
      val commandResponse = connection.play("/usr/share/freeswitch/sounds/en/us/callie/voicemail/8000/vm-tutorial_change_pin.wav")
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: playback\ncontent-type: text/plain\ncontent-length: 83\n\n/usr/share/freeswitch/sounds/en/us/callie/voicemail/8000/vm-tutorial_change_pin.wav\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "transfer function should generate FS transfer command" in new FSConnectionFixture {
      val commandResponse = connection.transfer("user/1000")
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: transfer\ncontent-type: text/plain\ncontent-length: 9\n\nuser/1000\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "hangup function should generate FS hangup command" in new FSConnectionFixture {
      val commandResponse = connection.hangup()
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: hangup\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "break function should generate FS break command" in new FSConnectionFixture {
      val commandResponse = connection.break()
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: break\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "answer function should generate FS answer command" in new FSConnectionFixture {
      val commandResponse = connection.answer()
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: answer\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "sendCommand function should send any FS command" in new FSConnectionFixture {
      val fsCmd = PlayFile("/usr/share/freeswitch/sounds/en/us/callie/voicemail/8000/vm-tutorial_change_pin.wav",
        ApplicationCommandConfig())
      val commandResponse = connection.sendCommand(fsCmd)
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: playback\ncontent-type: text/plain\ncontent-length: 83\n\n/usr/share/freeswitch/sounds/en/us/callie/voicemail/8000/vm-tutorial_change_pin.wav\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "sendCommand function should send plain FS command" in new FSConnectionFixture {
      val uuid = java.util.UUID.randomUUID().toString
      val plainCommand = s"sendmsg \nEvent-UUID: $uuid\ncall-command: execute\nexecute-app-name: playback\ncontent-type: text/plain\ncontent-length: 83\n\n/usr/share/freeswitch/sounds/en/us/callie/voicemail/8000/vm-tutorial_change_pin.wav\n"
      val commandResponse = connection.sendCommand(plainCommand, uuid)
      whenReady(commandResponse) {
        response =>
          response.command.toString shouldBe plainCommand
      }
    }

    "filter function should generate FS filter command" in new FSConnectionFixture {
      val commandResponse = connection.filter(Map(EventNames.ChannelExecute -> HeaderNames.eventName))
      whenReady(commandResponse) {
        response =>
          val fSCommand = "filter Event-Name CHANNEL_EXECUTE\n\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "deleteFilter function should generate FS deleteFilter command" in new FSConnectionFixture {
      val commandResponse = connection.deleteFilter(Map(EventNames.Heartbeat -> HeaderNames.eventName))
      whenReady(commandResponse) {
        response =>
          val fSCommand = "filter delete Event-Name HEARTBEAT\n\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "attXfer function should generate FS attXfer command" in new FSConnectionFixture {
      val commandResponse = connection.attXfer("user/5000")
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: att_xfer\ncontent-type: text/plain\ncontent-length: 71\n\n{attxfer_conf_key=0,attxfer_hangup_key=*,attxfer_cancel_key=#}user/5000\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "bridge function should generate FS bridge command" in new FSConnectionFixture {
      val commandResponse = connection.bridge(List("user/5000"), AllAtOnce)
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: bridge\ncontent-type: text/plain\ncontent-length: 9\n\nuser/5000\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "intercept function should generate FS intercept command" in new FSConnectionFixture {
      val uuid = java.util.UUID.randomUUID().toString
      val commandResponse = connection.intercept(uuid)
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: intercept\ncontent-type: text/plain\ncontent-length: 36\n\n${uuid}\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "read function should generate FS read command" in new FSConnectionFixture {
      val commandResponse = connection.read(2, 8)(
        "/usr/share/freeswitch/sounds/en/us/callie/voicemail/8000/vm-tutorial_music.wav",
        "res",
        Duration(100, MILLISECONDS)
      )
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: read\ncontent-type: text/plain\ncontent-length: 92\n\n2 8 /usr/share/freeswitch/sounds/en/us/callie/voicemail/8000/vm-tutorial_music.wav res 100 #\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "subscribeMyEvents function should generate FS subscribe myevents" in new FSConnectionFixture {
      val uuid = java.util.UUID.randomUUID().toString
      val commandResponse = connection.subscribeMyEvents(uuid)
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"myevents plain ${uuid}\n\n"
          response.command.toString shouldBe fSCommand
      }
    }


    "subscribeEvents function should generate FS subscribe events command" in new FSConnectionFixture {
      val commandResponse = connection.subscribeEvents(EventNames.All)
      whenReady(commandResponse) {
        response =>
          val fSCommand = "event plain ALL\n\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "sleep function should generate FS sleep events command" in new FSConnectionFixture {
      val commandResponse = connection.sleep(Duration(2, SECONDS))
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: sleep\ncontent-type: text/plain\ncontent-length: 4\n\n2000\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "setVar function should generate FS set command" in new FSConnectionFixture {
      val commandResponse = connection.setVar("call_timeout", "10")
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: set\ncontent-type: text/plain\ncontent-length: 15\n\ncall_timeout=10\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "preAnswer function should generate FS preAnswer command" in new FSConnectionFixture {
      val commandResponse = connection.preAnswer()
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: pre_answer\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "record function should generate FS record command" in new FSConnectionFixture {
      val commandResponse = connection.record("/tmp/record.mp3", Duration(5, SECONDS), Duration(3, SECONDS))
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: record\ncontent-type: text/plain\ncontent-length: 19\n\n/tmp/record.mp3 5 3\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "recordSession function should generate FS recordSession command" in new FSConnectionFixture {
      val commandResponse = connection.recordSession("/tmp/record.mp3")
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: record_session\ncontent-type: text/plain\ncontent-length: 15\n\n/tmp/record.mp3\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "sendDtmf function should generate FS sendDtmf command" in new FSConnectionFixture {
      val commandResponse = connection.sendDtmf("0123456789ABCD*#@100")
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: send_dtmf\ncontent-type: text/plain\ncontent-length: 20\n\n0123456789ABCD*#@100\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "stopRecordSession function should generate FS stopRecordSession command" in new FSConnectionFixture {
      val commandResponse = connection.stopRecordSession("/tmp/record.mp3")
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: stop_record_session\ncontent-type: text/plain\ncontent-length: 15\n\n/tmp/record.mp3\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "park function should generate FS park command" in new FSConnectionFixture {
      val commandResponse = connection.park()
      whenReady(commandResponse) {
        response =>
          val fSCommand = s"sendmsg \nEvent-UUID: ${response.command.eventUuid}\ncall-command: execute\nexecute-app-name: park\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "log function should generate FS log command" in new FSConnectionFixture {
      val commandResponse = connection.log("DIALING Extension DialURI [${sip_uri_to_dial}]")
      whenReady(commandResponse) {
        response =>
          val fSCommand = "log DIALING Extension DialURI [${sip_uri_to_dial}]\n\n"
          response.command.toString shouldBe fSCommand
      }
    }

    "exit function should generate FS exit command" in new FSConnectionFixture {
      val commandResponse = connection.exit()
      whenReady(commandResponse) {
        response =>
          val fSCommand = "exit\n\n"
          response.command.toString shouldBe fSCommand
      }
    }

  }
}

