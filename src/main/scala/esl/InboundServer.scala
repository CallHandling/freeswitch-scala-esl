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

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.MarkerLoggingAdapter
import akka.stream.Materializer
import akka.stream.scaladsl.{BidiFlow, Flow, Sink, Source, Tcp}
import akka.util.ByteString
import com.typesafe.config.Config
import esl.FSConnection.{FSData, FSSocket}

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

object InboundServer {
  private val address = "freeswitch.inbound.address"
  private val port = "freeswitch.inbound.port"
  private val fsTimeout = "freeswitch.inbound.startup.timeout"
  private val linger = "freeswitch.outbound.linger"
  private val defaultTimeout = Duration(5, SECONDS)

  /**
    * Create a inbound client for a given configuration and parser
    *
    * @param config       : Config this configuration must have `freeswitch.inbound.address` and `freeswitch.inbound.address`
    * @param system       : ActorSystem
    * @param materializer : Materializer
    * @return OutboundServer
    */
  def apply(config: Config)
           (implicit system: ActorSystem, materializer: Materializer, adapter: MarkerLoggingAdapter): InboundServer =
    new InboundServer(config)

  /**
    * This will create a InBound client for a given interface and port
    *
    * @param interface    : String name/ip address of the server
    * @param port         : Int port number of the server
    * @param system       : ActorSystem
    * @param materializer : Materializer
    * @return OutboundServer
    */
  def apply(interface: String, port: Int, timeout: FiniteDuration = defaultTimeout,linger: Boolean = true)
           (implicit system: ActorSystem, materializer: Materializer, adapter: MarkerLoggingAdapter): InboundServer =
    new InboundServer(interface, port, timeout, linger)

}

class InboundServer(interface: String, port: Int, timeout: FiniteDuration, linger:Boolean)
                   (implicit system: ActorSystem, materializer: Materializer, adapter: MarkerLoggingAdapter) {
  implicit private val ec = system.dispatcher

  def this(config: Config)
          (implicit system: ActorSystem, materializer: Materializer, adapter: MarkerLoggingAdapter) =
    this(config.getString(InboundServer.address),
      config.getInt(InboundServer.port),
      Duration(config.getDuration(InboundServer.fsTimeout).getSeconds, SECONDS),
      config.getBoolean(InboundServer.linger))

  /**
    * Open a client connection for given interface and port
    *
    * @param sink materialize upstream element
    * @param flow flow from source to bi-directional
    * @tparam T1 element materialize from upstream
    * @tparam T2 element publish to downstream
    * @return
    */
  private[this] def client[T1, T2, Mat1, Mat2](sink: Sink[T1, Mat1],
                                   flow: (Source[T2, Mat2], BidiFlow[ByteString, T1, T2, ByteString, NotUsed])) = {
    val clientFlow = Tcp().outgoingConnection(interface, port)
    val (source, protocol) = flow
    val flowWithProtocol: Flow[T2, T1, Future[Tcp.OutgoingConnection]] = clientFlow
      .join(protocol)
      flowWithProtocol.runWith(source, sink)
  }


  /**
    * The connect() function will authenticate client with freeswitch using given password. If freeswitch is not respond within given time then connection will timeout.
    *
    * @param password : String password for connection to freeswitch
    * @param fun      function will get freeswitch outbound connection after injecting sink
    * @return Future[(Any, Any)]
    */
  def connect[Mat](password: String)(fun: Future[FSSocket[InboundFSConnection]] => Sink[FSData, Mat]): Future[(NotUsed, Future[Any])] = {
    val fsConnection = InboundFSConnection()
    fsConnection.connect(password).map { _ =>
      val sink = fsConnection.init(Promise[FSSocket[InboundFSConnection]](), fsConnection, fun, timeout, linger)
      client(sink, fsConnection.handler())
    }
  }


}