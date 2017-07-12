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
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.util.ByteString
import esl.domain.CallCommands._
import domain.{ApplicationCommandConfig, _}
import esl.parser.Parser
import akka.pattern.after

import scala.concurrent.{ExecutionContextExecutor, Future, Promise, TimeoutException}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

trait FSConnection {
  val parser: Parser
  implicit protected val system: ActorSystem
  implicit protected val materializer: ActorMaterializer
  lazy implicit protected val ec: ExecutionContextExecutor = system.dispatcher
  private[this] var unParsedBuffer = ""
  lazy val (queue, source) = Source.queue[FSCommand](50, OverflowStrategy.backpressure)
    .toMat(Sink.asPublisher(false))(Keep.both).run()

  /**
    * It will parsed incoming packet into free switch messages. If there is an unparsed packet from last received packets
    * will append to the next received packets. So we will get complete parsed packet.
    */
  private[this] val incoming = Flow[ByteString].map { f =>
    val (messages, buffer) = parser.parse(unParsedBuffer + f.utf8String)
    unParsedBuffer = buffer
    println("incoming:::"+messages)
    messages
  }

  /**
    * It will convert free switch command into ByteString
    */
  private[this] val outgoing: Flow[FSCommand, ByteString, _] = Flow.fromFunction {
    freeSwitchCommand => ByteString(freeSwitchCommand.toString)
  }

  /**
    * The function handler will create complete pipeline for incoming and outgoing data
    *
    * @param flow Flow[ByteString, List[FreeSwitchMessage], _] => Flow[ByteString, T, _]
    *             It will transform parsed FreeSwitchMessage into type `T` data
    * @tparam T type `T` is transformed from FreeSwitchMessage
    * @return (Source[FreeSwitchCommand, NotUsed], BidiFlow[ByteString, T, FreeSwitchCommand, ByteString, NotUsed])
    *         return tuple of source and flow
    */
  def handler[T](flow: Flow[ByteString, List[FSMessage], _] => Flow[ByteString, T, _]): (Source[FSCommand, NotUsed], BidiFlow[ByteString, T, FSCommand, ByteString, NotUsed]) = {
    (Source.fromPublisher(source), BidiFlow.fromFlows(flow(incoming), outgoing))
  }

  /**
    * handler() function will create pipeline for source and flow
    *
    * @return Source[FreeSwitchCommand, NotUsed], BidiFlow[ByteString, List[FreeSwitchMessage], FreeSwitchCommand, ByteString, NotUsed]
    *         tuple of source and flow
    */
  def handler(): (Source[FSCommand, NotUsed], BidiFlow[ByteString, List[FSMessage], FSCommand, ByteString, NotUsed]) = {
    (Source.fromPublisher(source), BidiFlow.fromFlows(incoming, outgoing))
  }

  def init[FS <: FSConnection, T](fsConnectionPromise: => Promise[FS],
                                  fsConnection: FS,
                                  fun: (Future[FS]) => Sink[List[T], _],
                                  timeout: FiniteDuration): Sink[List[T], NotUsed] = {
    var hasAuthenticated = false
    lazy val timeoutFuture = after(duration = timeout, using = system.scheduler) {
      Future.failed(new TimeoutException(s"Socket doesn't receive any response within $timeout."))
    }
    val fsConnectionFuture = Future.firstCompletedOf(Seq(fsConnectionPromise.future, timeoutFuture))
    Flow[List[T]].map { fsMessages =>
      println(":::fsMessages:::" + fsMessages)
      if (!hasAuthenticated) {
        fsMessages.collectFirst {
          case command: CommandReply => command
        }.foreach { command =>
          if (command.contentType == ContentTypes.commandReply && command.success) {
            fsConnectionPromise.complete(Success(fsConnection))
            hasAuthenticated = true
          } else {
            fsConnectionPromise.complete(Failure(new Exception(s"Socket failed to make connection with an error: ${command.errorMessage}")))
          }
        }
      }
      fsMessages
    }.to(fun(fsConnectionFuture))
  }

  def connect(auth: String): Future[QueueOfferResult]

  def play(fileName: String, config: ApplicationCommandConfig = ApplicationCommandConfig()): CommandRequest = {
    val playFile = PlayFile(fileName, config)
    CommandRequest(playFile, queue.offer(playFile))
  }

  def transfer(extension: String, config: ApplicationCommandConfig = ApplicationCommandConfig()): CommandRequest = {
    val transferTo = TransferTo(extension, config)
    CommandRequest(transferTo, queue.offer(transferTo))
  }

  def hangup(config: ApplicationCommandConfig = ApplicationCommandConfig()): CommandRequest = {
    val hangup = Hangup(config)
    CommandRequest(hangup, queue.offer(hangup))
  }

  def break(config: ApplicationCommandConfig = ApplicationCommandConfig()): CommandRequest = {
    val break = Break(config)
    CommandRequest(break, queue.offer(break))
  }

  def sendCommand(command: FSCommand): CommandRequest = CommandRequest(command, queue.offer(command))

  def sendCommand(command: String): CommandRequest = {
    val commandAsString = CommandAsString(command)
    CommandRequest(commandAsString, queue.offer(commandAsString))
  }
}
