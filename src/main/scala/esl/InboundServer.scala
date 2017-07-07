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
import akka.pattern.after
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{BidiFlow, Flow, Sink, Source, Tcp}
import akka.util.ByteString
import com.typesafe.config.Config
import esl.domain.{CommandReply, ContentTypes, FSMessage}
import esl.parser.Parser

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise, _}
import scala.util.{Failure, Success}

object InboundServer {
  val address = "freeswitch.inbound.address"
  val port = "freeswitch.inbound.port"

  /**
    * Create a inbound client for a given configuration and parser
    *
    * @param config       : Config this configuration must have `freeswitch.inbound.address` and `freeswitch.inbound.address`
    * @param parser       : Parser This parser will parse any incoming message into Free switch messages
    * @param system       : ActorSystem
    * @param materializer : ActorMaterializer
    * @return OutboundServer
    */
  def apply(config: Config, parser: Parser)(implicit system: ActorSystem, materializer: ActorMaterializer): InboundServer =
    new InboundServer(config, parser)

  /**
    * This will create a InBound client for a given interface and port
    *
    * @param interface    : String name/ip address of the server
    * @param port         : Int port number of the server
    * @param parser       : Parser it will parse any incoming packet into Free switch messages
    * @param system       : ActorSystem
    * @param materializer : ActorMaterializer
    * @return OutboundServer
    */
  def apply(interface: String, port: Int, parser: Parser)(implicit system: ActorSystem, materializer: ActorMaterializer): InboundServer =
    new InboundServer(interface, port, parser)

  /**
    * This will create a OutBound server for localhost's default free switch server
    *
    * @param parser       : Parser it will parse any incoming packet into Free switch messages
    * @param system       : ActorSystem
    * @param materializer : ActorMaterializer
    * @return OutboundServer
    */
  def apply(parser: Parser)(implicit system: ActorSystem, materializer: ActorMaterializer): InboundServer =
    new InboundServer(interface = "localhost", port = 8021, parser)
}

class InboundServer(interface: String, port: Int, parser: Parser)(implicit system: ActorSystem, materializer: ActorMaterializer) {
  implicit private val ec = system.dispatcher

  def this(config: Config, parser: Parser)(implicit system: ActorSystem, materializer: ActorMaterializer) =
    this(config.getString(InboundServer.address), config.getInt(InboundServer.port), parser)

  /**
    * Open a client connection for given interface and port
    *
    * @param sink materialize upstream element
    * @param flow flow from source to bi-directional
    * @tparam T1 element materialize from upstream
    * @tparam T2 element publish to downstream
    * @return
    */
  private[this] def client[T1, T2](sink: Sink[T1, _],
                                   flow: (Source[T2, _], BidiFlow[ByteString, T1, T2, ByteString, NotUsed])) = {
    val clientFlow = Tcp().outgoingConnection(interface, port)
    val (source, protocol) = flow
    clientFlow
      .join(protocol)
      .runWith(source, sink)
  }


  /**
    * The connect() function will authenticate client with freeswitch using given password. If freeswitch is not respond within given time then connection will timeout.
    *
    * @param password : String password for connection to freeswitch
    * @param timeout  : FiniteDuration
    * @param fun      function will get freeswitch outbound connection after injecting sink
    * @return Future[(Any, Any)]
    */
  def connect(password: String, timeout: FiniteDuration)(fun: Future[InboundFSConnection] => Sink[List[FSMessage], _]): Future[(Any, Any)] = {
    var hasAuthenticated = false
    val fsConnection = InboundFSConnection(parser)
    fsConnection.connect(password).map { _ =>
      val fsConnectionPromise = Promise[InboundFSConnection]()
      lazy val timeoutFuture = after(duration = timeout, using = system.scheduler) {
        Future.failed(new TimeoutException(s"Inbound socket doesn't receive any response within $timeout time!"))
      }
      val fsConnectionFuture = Future.firstCompletedOf(Seq(fsConnectionPromise.future, timeoutFuture))

      val sink = Flow[List[FSMessage]].map { freeSwitchMessages =>
        if (!hasAuthenticated) {
          freeSwitchMessages.collectFirst {
            case command: CommandReply => command
          }.foreach { command =>
            if (command.contentType == ContentTypes.commandReply && command.success) {
              fsConnectionPromise.complete(Success(fsConnection))
              hasAuthenticated = true
            } else {
              fsConnectionPromise.complete(Failure(new Exception(s"Inbound connection failed with socket: ${command.errorMessage}")))
            }
          }
        }
        freeSwitchMessages
      }.to(fun(fsConnectionFuture))

      client(sink, fsConnection.handler())
    }
  }
}