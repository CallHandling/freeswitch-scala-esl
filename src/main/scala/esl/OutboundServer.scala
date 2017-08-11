/*
 * Copyright 2017 Call Handling Services Ltd.
 * <http://www.callhandling.co.uk>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package esl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Tcp.IncomingConnection
import akka.stream.scaladsl.{BidiFlow, Sink, Source, Tcp}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.config.Config
import esl.FSConnection.{FSData, FSSocket}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object OutboundServer {
  private val address = "freeswitch.outbound.address"
  private val port = "freeswitch.outbound.port"
  private val fsTimeout = "freeswitch.outbound.startup.timeout"
  private val defaultTimeout = Duration(1, SECONDS)

  /**
    * Create a OutBound server with given configuration and parser
    *
    * @param config       : Config this configuration must have `freeswitch.outbound.address` and `freeswitch.outbound.address`
    * @param system       : ActorSystem
    * @param materializer : ActorMaterializer
    * @return OutboundServer
    */
  def apply(config: Config)
           (implicit system: ActorSystem, materializer: ActorMaterializer): OutboundServer =
    new OutboundServer(config)

  /**
    * This will create a OutBound server for given interface and port
    *
    * @param interface    : String name/ip address of the server
    * @param port         : Int port number of the server
    * @param system       : ActorSystem
    * @param materializer : ActorMaterializer
    * @return OutboundServer
    */
  def apply(interface: String, port: Int, timeout: FiniteDuration = defaultTimeout)
           (implicit system: ActorSystem, materializer: ActorMaterializer): OutboundServer =
    new OutboundServer(interface, port, timeout)

}


class OutboundServer(address: String, port: Int, timeout: FiniteDuration)
                    (implicit system: ActorSystem, materializer: ActorMaterializer) extends Logging {
  implicit private val ec = system.dispatcher

  def this(config: Config)
          (implicit system: ActorSystem, materializer: ActorMaterializer) =
    this(config.getString(OutboundServer.address),
      config.getInt(OutboundServer.port),
      Duration(config.getDuration(OutboundServer.fsTimeout).getSeconds, SECONDS))

  /** This function will start a tcp server with given Sink. any free switch messages materialize to given sink.
    * `FsConnection`'s helper function allow you to send/push message to downstream.
    *
    * @param fun                  this function will receive Fs connection and any incoming Free switch message materialize to the given sink.
    * @param onFsConnectionClosed this function will execute when Fs connection is closed.
    * @return The stream is completed successfully or not
    */
  def startWith(fun: Future[FSSocket[OutboundFSConnection]] => Sink[FSData, _],
                onFsConnectionClosed: Future[IncomingConnection] => Unit): Future[Done] =
    server(fun, fsConnection => fsConnection.handler(), onFsConnectionClosed)

  /** This function will start a tcp server for given sink,source and flow.
    *
    * @param fun                  this function will receive Fs connection and any incoming message materialize to the given sink
    * @param flow                 Source's element `T2` push to downstream
    *                             BidiFlow will transform incoming ByteString into `T1` type and outgoing `T2` into ByteString
    * @param onFsConnectionClosed this function will execute when Fs connection is closed
    * @tparam T type of data transform into ByteString
    * @return The stream is completed successfully or not
    */
  private[this] def server[T](fun: Future[FSSocket[OutboundFSConnection]] => Sink[FSData, _],
                              flow: OutboundFSConnection => (Source[T, _], BidiFlow[ByteString, FSData, T, ByteString, NotUsed]),
                              onFsConnectionClosed: Future[IncomingConnection] => Unit): Future[Done] = {
    Tcp().bind(address, port).runForeach {
      connection =>
        logger.info(s"Socket connection is opened for ${connection.remoteAddress}")
        val fsConnection = OutboundFSConnection()
        fsConnection.connect().map { _ =>
          val sink = fsConnection.init(Promise[FSSocket[OutboundFSConnection]](), fsConnection, fun, timeout)
          val (source, protocol) = flow(fsConnection)
          val (_, closed: Future[Any]) = connection.flow
            .join(protocol)
            .runWith(source, sink)
          val closedConn = closed.transform {
            case Success(_) =>
              logger.info(s"Socket connection has been closed successfully for ${connection.remoteAddress}")
              Success(connection)
            case Failure(ex) =>
              logger.info(s"Socket connection failed to closed for ${connection.remoteAddress}")
              Failure(ex)
          }
          onFsConnectionClosed(closedConn)
        }
    }
  }
}
