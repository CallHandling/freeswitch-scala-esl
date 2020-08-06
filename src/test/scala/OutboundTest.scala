import akka.Done
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import esl.FSConnection.FSData
import esl.OutboundServer
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Created by abdhesh on 28/06/17.
  */

object OutboundTest extends App with Logging {
  //You need the normal akka implicits (see akka documentation)
  implicit val system = ActorSystem("esl-system")
  implicit val actorMaterializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  OutboundServer("127.0.0.1", 8084)
    .startWith(
      fsSocket => {

        /** For each outbound connection from freeswitch you will get a future named here 'fsSocket' this future will complete when we get a response from freeswitch to a connect command that is sent automatically by the library */

        val result: Future[Sink[FSData, Future[Done]]] = fsSocket
          .map {
            socket =>
              /** every command you send will return a future of the result from freeswitch, we just use foreach to get in to
            the future success here, you can use the future however you like including adding an onComplete callback*/
              val uuid = socket.channelData.uuid.getOrElse("")
              socket.fsConnection
                .subscribeMyEvents(uuid)
                .foreach {
                  _ =>
                    /** commands that execute applications will return a CommandResponse which has 3 futures. See below: */
                    socket.fsConnection
                      .play(
                        "/usr/share/freeswitch/sounds/en/us/callie/conference/8000/conf-pin.wav"
                      )
                      .foreach {
                        commandResponse =>
                          /** This future will complete when FreeSwitch sends command/reply message to the socket.
                        It will be Success or Failure based on the response from FreeSwitch*/
                          commandResponse.commandReply.foreach(f =>
                            logger.info(s"Got command reply: ${f}")
                          )

                          /** This future will complete when FreeSwitch sends the CHANNEL_EXECUTE event to the socket */
                          commandResponse.executeEvent.foreach(f =>
                            logger.info(s"Got ChannelExecute event: ${f}")
                          )

                          /** This future will complete when FreeSwitch sends the CHANNEL_EXECUTE_COMPLETE  event to the socket */
                          commandResponse.executeComplete.foreach(f =>
                            logger
                              .info(s"Got ChannelExecuteComplete event: ${f}")
                          )
                      }
                }

              /** You can push in a Sink of FSMessage to create a reactive pipeline for all the events coming down the socket */
              Sink.foreach[FSData] { fsData =>
                /**Here you have an access of fs connection along with fs messages*/
                logger.info(s"Fs Messages: ${fsData.fsMessages}")
              }
          }
          .recoverWith {
            case ex =>
              println("failed to make outbound socket connection", ex)
              Future.successful(
                Sink
                  .cancelled[FSData]
                  .mapMaterializedValue(_ => Future.successful(akka.Done))
              )
          }

        Sink.futureSink(result)

      },
      result =>
        result onComplete {
          case Success(conn) =>
            logger.info(s"Connection closed normally ${conn.localAddress}")
          case Failure(ex) =>
            logger
              .info(s"Connection with freeswitch closed with exception: ${ex}")
        }
    )
    .onComplete {
      case Success(result) =>
        logger.info(s"TCP Listener started successfully ${result}")
      case Failure(ex) => logger.info(s"TCP Listener Failed to start ${ex}")
    }
}
