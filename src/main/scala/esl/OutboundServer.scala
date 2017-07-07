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
import akka.stream.scaladsl.{BidiFlow, Flow, Sink, Source, Tcp}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.config.Config
import esl.domain.FSMessage
import esl.parser.Parser

import scala.concurrent.Future

object OutboundServer {
  val address = "freeswitch.outbound.address"
  val port = "freeswitch.outbound.port"

  /**
    * Create a OutBound server with given configuration and parser
    *
    * @param config       : Config this configuration must have `freeswitch.outbound.address` and `freeswitch.outbound.address`
    * @param parser       : Parser This parser will parse any incoming message into Free switch messages
    * @param system       : ActorSystem
    * @param materializer : ActorMaterializer
    * @return OutboundServer
    */
  def apply(config: Config, parser: Parser)(implicit system: ActorSystem, materializer: ActorMaterializer): OutboundServer =
    new OutboundServer(config, parser)

  /**
    * This will create a OutBound server for given interface and port
    *
    * @param interface    : String name/ip address of the server
    * @param port         : Int port number of the server
    * @param parser       : Parser it will parse any incoming packet into Free switch messages
    * @param system       : ActorSystem
    * @param materializer : ActorMaterializer
    * @return OutboundServer
    */
  def apply(interface: String, port: Int, parser: Parser)(implicit system: ActorSystem, materializer: ActorMaterializer): OutboundServer =
    new OutboundServer(interface, port, parser)

}


class OutboundServer(address: String, port: Int, parser: Parser)(implicit system: ActorSystem, materializer: ActorMaterializer) {

  def this(config: Config, parser: Parser)(implicit system: ActorSystem, materializer: ActorMaterializer) =
    this(config.getString(OutboundServer.address), config.getInt(OutboundServer.port), parser)

  /** This function will start a tcp server with given Sink and flow. `FsConnection`'s helper functions allow you to send/push messages to the downstream.
    *
    * @param fun                  this function will receive Fs connection and any incoming message materialize to the given sink.
    * @param flow                 this function will receive a FreeSwitchMessage and transform into `T` message.
    * @param onFsConnectionClosed this will execute when Fs connection is closed.
    * @tparam T type of message transform from FreeSwitchMessage must materialize to the given sink.
    * @return The stream is completed successfully or not.
    */
  def startWith[T](fun: FSConnection => Sink[T, _],
                   flow: Flow[ByteString, List[FSMessage], _] => Flow[ByteString, T, _],
                   onFsConnectionClosed: Future[Any] => Unit): Future[Done] =
    server(fun, fsConnection => fsConnection.handler(flow), onFsConnectionClosed)

  /** This function will start a tcp server with given Sink. So any free switch messages materialize to given sink.
    * `FsConnection`'s helper function allow you to send/push message to downstream.
    *
    * @param fun                  this function will receive Fs connection and any incoming Free switch message materialize to the given sink.
    * @param onFsConnectionClosed this function will execute when Fs connection is closed.
    * @return The stream is completed successfully or not
    */
  def startWith(fun: FSConnection => Sink[List[FSMessage], _],
                onFsConnectionClosed: Future[Any] => Unit): Future[Done] =
    server(fun, fsConnection => fsConnection.handler(), onFsConnectionClosed)

  /** This function will start a tcp server for given sink,source and flow.
    *
    * @param fun                  this function will receive Fs connection and any incoming message materialize to the given sink
    * @param flow                 Source's element `T2` push to downstream
    *                             BidiFlow will transform incoming ByteString into `T1` type and outgoing `T2` into ByteString
    * @param onFsConnectionClosed this function will execute when Fs connection is closed
    * @tparam T1 type of data transformed from ByteString
    * @tparam T2 type of data transform into ByteString
    * @return The stream is completed successfully or not
    */
  private[this] def server[T1, T2](fun: FSConnection => Sink[T1, _],
                                   flow: FSConnection => (Source[T2, _], BidiFlow[ByteString, T1, T2, ByteString, NotUsed]),
                                   onFsConnectionClosed: Future[Any] => Unit): Future[Done] = {
    Tcp().bind(address, port).runForeach {
      connection =>
        val fsConnection = OutboundFSConnection(parser)
        val sink = fun(fsConnection)
        val (source, protocol) = flow(fsConnection)
        val (_, closed: Future[Any]) = connection.flow
          .join(protocol)
          .runWith(source, sink)
        onFsConnectionClosed(closed)
    }
  }
}
