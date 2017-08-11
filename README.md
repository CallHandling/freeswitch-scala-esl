# freeswitch-scala-esl - Not yet used in production
A reactive event socket library for FreeSwitch written using Scala and Akka Streams

This library is intended to make the use of FreeSwitch easier for Scala developers. As a second phase we will consider writing a mod for FreeSwitch which supports reactive integration including backpressure et al. 

Here is a brief example of how to use the library. For a full list of supported commands you can see in the source.

# Outbound Mode

This will bind to a host / port in your system and wait for connections coming from freeswitch.  

```scala
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.FSConnection.FSData
import esl.OutboundServer
import org.apache.logging.log4j.scala.Logging

import scala.util.{Failure, Success}

/**
  * Created by abdhesh on 28/06/17.
  */


object OutboundTest extends App with Logging {
  //You need the normal akka implicits (see akka documentation)
  implicit val system = ActorSystem("esl-system")
  implicit val actorMaterializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  OutboundServer("127.0.0.1", 8084).startWith(
    fsSocket => {
      /** For each outbound connection from freeswitch you will get a future named here 'fsSocket' this future will complete when we get a response from freeswitch to a connect command that is sent automatically by the library */
      fsSocket.onComplete {
        case Success(socket) =>
        /** every command you send will return a future of the result from freeswitch, we just use foreach to get in to 
            the future success here, you can use the future however you like including adding an onComplete callback*/
          val uuid = socket.channelData.uuid.getOrElse("")
          socket.fsConnection.subscribeMyEvents(uuid).foreach {
            _ =>
            /** commands that execute applications will return a CommandResponse which has 3 futures. See below: */
              socket.fsConnection.play("/usr/share/freeswitch/sounds/en/us/callie/conference/8000/conf-pin.wav").foreach {
                commandResponse =>

                 /** This future will complete when FreeSwitch sends command/reply message to the socket. 
                      It will be Success or Failure based on the response from FreeSwitch*/
                  commandResponse.commandReply.foreach(f => logger.info(s"Got command reply: ${f}"))

                 /** This future will complete when FreeSwitch sends the CHANNEL_EXECUTE event to the socket */
                  commandResponse.executeEvent.foreach(f => logger.info(s"Got ChannelExecute event: ${f}"))

                  /** This future will complete when FreeSwitch sends the CHANNEL_EXECUTE_COMPLETE  event to the socket */
                  commandResponse.executeComplete.foreach(f => logger.info(s"Got ChannelExecuteComplete event: ${f}"))
              }
          }
           /** The connect command returned ERR from freeswitch or timed out */
        case Failure(ex) => logger.info("failed to make outbound socket connection", ex)
      }
      /** You can push in a Sink of FSMessage to create a reactive pipeline for all the events coming down the socket */
      Sink.foreach[FSData] { fsData =>
      /**Here you have an access of fs connection along with fs messages*/
        logger.info(s"Fs Messages: ${fsData.fsMessages}")
      }
    },
    result => result onComplete {
      case Success(conn) => logger.info(s"Connection has closed normally ${conn.localAddress}")
      case Failure(ex) => logger.info(s"Connection with freeswitch closed with exception: ${ex}")
    }
  ).onComplete {
    case Success(result) => logger.info(s"TCP Listener started successfully ${result}")
    case Failure(ex) => logger.info(s"TCP Listener Failed to start ${ex}")
  }
}
```

# Inbound Mode

This mode will make a connection to FreeSwitch and connect using the password supplied. It will wait up to the duration supplied for a response before returning a Timeout exception to the returned future in the event it times out. 

```scala
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.FSConnection.FSData
import esl.InboundServer
import esl.domain.EventNames.{All, ChannelAnswer}
import esl.domain.{ApplicationCommandConfig, EventMessage}
import org.apache.logging.log4j.scala.Logging

import scala.util.{Failure, Success}


object InboundTest extends App with Logging {
  implicit val system = ActorSystem()
  implicit val mater = ActorMaterializer()
  implicit val ec = system.dispatcher


  InboundServer("localhost", 8021).connect("ClueCon") {
    fsSocket =>
      /** Inbound fsConnection future will get completed when client is authorised by freeswitch */
      fsSocket.onComplete {
        case Success(socket) =>
          socket.fsConnection.subscribeEvents(All).foreach {
            commandResponse =>
              commandResponse.commandReply.foreach(f => logger.info(f))
          }
        case Failure(ex) => logger.info("failed to make inbound socket connection", ex)
      }
      Sink.foreach[FSData] { fsData =>
      /**Here you have an access of fs connection along with fs messages*/
        fsData.fsMessages.collect {
          case event: EventMessage if event.eventName.contains(ChannelAnswer) =>
            val uuid = event.uuid.getOrElse("")
            fsData.fSConnection.play("/usr/share/freeswitch/sounds/en/us/callie/conference/8000/conf-pin.wav",
              ApplicationCommandConfig(uuid)).foreach {
              commandResponse =>

                /** This future will get complete, when FS send command/reply message to the socket */
                commandResponse.commandReply.foreach(f => logger.info(s"Got command reply: ${f}"))

                /** This future will get complete, when FS send CHANNEL_EXECUTE event to the socket */
                commandResponse.executeEvent.foreach(f => logger.info(s"Got ChannelExecute event: ${f}"))

                /** This future will get complete, when FS send CHANNEL_EXECUTE_COMPLETE  event to the socket */
                commandResponse.executeComplete.foreach(f => logger.info(s"ChannelExecuteComplete event: ${f}"))
            }
        }
        logger.info(fsData.fsMessages)
      }
  }.onComplete {
    case Success(result) => logger.info(s"Inbound socket started successfully ${result}")
    case Failure(ex) => logger.info(s"Inbound socket failed to start with exception ${ex}")
  }
}
```

Some of the commands we have already implmented, details of the funcitons and parameters etc can be found in the code base:-

* Connect
* Auth
* subscribeEvents
* myevents
* filterEvents
* Hangup
* Break
* Play
* Transfer
* Answer
* Auth
* SetVar
* Attended Transfer
* Bridge
* Connect
* Subscribe Events
* Filter Events
* Intercept
* ReadDTMF
* SayPhrase
* Sleep
* PreAnswer
* Record
* Record Session
* Stop Record
* Send DTMF
* Park
* Exit
* Log  

