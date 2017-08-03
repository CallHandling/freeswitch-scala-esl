# freeswitch-scala-esl - Not yet used in production
A reactive event socket library for FreeSwitch written using Scala and Akka Streams

This library is intended to make the use of FreeSwitch easier for Scala developers. As a second phase we will consider writing a mod for FreeSwitch which supports reactive integration including backpressure et al. 

Here is a brief example of how to use the library. For a full list of supported commands you can see in the source.

# Outbound Mode

This will bind to a host / port in your system and wait for connections coming from freeswitch.  

```scala
import java.util.Locale

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.OutboundServer
import esl.domain.EventNames.All
import esl.domain._
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Future
import scala.concurrent.duration.{Duration, MILLISECONDS, SECONDS}
import scala.util.{Failure, Success}

//You need the normal akka implicits (see akka documentation)
implicit val system = ActorSystem("esl-system")
implicit val actorMaterializer = ActorMaterializer()
implicit val ec = system.dispatcher

OutboundServer("127.0.0.1", 8084).startWith(
  fsSocket => {
    /** For each outbound connection from freeswitch you will get a future named here 'fsConnection' this future will complete when we get a response from freeswitch to a connect command that is sent automatically by the library. */
    fsSocket.onComplete {
      case Success(socket) =>
        /** every command you send will return a future of the result from freeswitch, we just use foreach to get in to 
        the future success here, you can use the future however you like including adding an onComplete callback*/
        val uuid = socket.reply.headers.get(HeaderNames.uniqueId).getOrElse("")
        socket.fsConnection.subscribeMyEvents(uuid).foreach {
          _ =>
            /** commands that exzecute applicaitons will return a ComandResponse which has 3 futures. See below: */
            socket.fsConnection.play("<filepath>").foreach {    
              commandResponse =>
                /** This future will complete when FreeSwitch sends command/reply message to the socket. 
                It will be Success or Failure based on the response from FreeSwitch*/
                commandResponse.commandReply.foreach(f => logger.info(s"Got command reply: ${f}"))

                /** This future will complete when FreeSwitch sends the CHANNEL_EXECUTE event to the socket */
                commandResponse.executeEvent.foreach(f => logger.info(s"Got ChannelExecute event: ${f}"))

                /** This future will complete when FreeSwitch sends the CHANNEL_EXECUTE_COMPLETE  event to the socket */
                commandResponse.executeComplete.foreach(f => logger.info(s"ChannelExecuteComplete event: ${f}"))
            }
        }
       /** The connect command returned ERR from freeswitch or timed out */
      case Failure(ex) => logger.info("failed to make outbound socket connection", ex)
    }
    /** You can push in a Sink of FSMessage to create a reactive pipeline for all the events coming down the socket */
    Sink.foreach[List[FSMessage]] { fsMessages => 
      logger.info(fsMessages) 
    }    
  },
  result => result onComplete {
    case Success(conn) => logger.info(s"Connection with freeswitch closed normally ${conn.localAddress}")
    case Failure(ex) => logger.info(s"Connection with freeswitch closed with exception: ${ex}")
  }
).onComplete {
  case Success(result) => logger.info(s"TCP Listener started successfully ${result}")
  case Failure(ex) => logger.info(s"TCP Listener Failed to start ${ex}")
}
```

# Inbound Mode

This mode will make a connection to FreeSwitch and connect using the password supplied. It will wait up to the duration supplied for a response before returning a Timeout exception to the returned future in the event it times out. 

```scala
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.InboundServer
import esl.domain.{EventNames, FSMessage}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._
import scala.util.{Failure, Success}

implicit val system = ActorSystem()
implicit val mater = ActorMaterializer()
implicit val ec = system.dispatcher

InboundServer("localhost", 8021).connect("ClueCon") {
  fSSocket =>
    /**Inbound fsConnection future will get completed when client is authorised by freeswitch*/
    fSSocket.onComplete {
      case Success(socket) =>
        /** You can also subscribe to individual events by separating with a comma in to the subscribeEvents params**/
        socket.fsConnection.subscribeEvents(EventNames.All).foreach {
          _ =>
            socket.fsConnection.play("<filepath>").foreach {
              commandResponse =>
                commandResponse.commandReply.foreach(f => logger.info(s"Got command reply: ${f}"))

                commandResponse.executeEvent.foreach(f => logger.info(s"Got ChannelExecute event: ${f}"))

                commandResponse.executeComplete.foreach(f => logger.info(s"ChannelExecuteComplete event: ${f}"))
            }
        }
      case Failure(ex) => logger.info("failed to make inbound socket connection", ex)
    }
    Sink.foreach[List[FSMessage]] { fsMessages => logger.info(fsMessages) }
}.onComplete {
  case Success(result) => logger.info(s"Inbound socket started successfully ${result}")
  case Failure(ex) => logger.info(s"Inbound socket failed to start with exception ${ex}")
}
```


