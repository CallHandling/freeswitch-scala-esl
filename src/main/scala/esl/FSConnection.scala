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
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.util.ByteString
import esl.domain.CallCommands._
import esl.domain.EventNames.EventName
import esl.domain.{ApplicationCommandConfig, FSMessage, _}
import esl.parser.DefaultParser
import org.apache.logging.log4j.scala.Logging

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future, Promise, TimeoutException}
import scala.util.{Failure, Success}

trait FSConnection extends Logging {
  lazy val parser = DefaultParser
  implicit protected val system: ActorSystem
  implicit protected val materializer: ActorMaterializer
  lazy implicit protected val ec: ExecutionContextExecutor = system.dispatcher
  private[this] var unParsedBuffer = ""
  protected val bgapiMap: mutable.Map[String, Promise[CommandReply]] = mutable.Map.empty
  val commandQueue: mutable.Queue[Promise[CommandReply]] = mutable.Queue.empty
  lazy val (queue, source) = Source.queue[FSCommand](50, OverflowStrategy.backpressure)
    .toMat(Sink.asPublisher(false))(Keep.both).run()

  /**
    * It will parsed incoming packet into free switch messages. If there is an unparsed packet from last received packets,
    * will append to the next received packets. So we will get the complete parsed packet.
    */
  private[this] val incoming = Flow[ByteString].map { f =>
    val (messages, buffer) = parser.parse(unParsedBuffer + f.utf8String)
    unParsedBuffer = buffer
    messages
  }

  /**
    * It will convert the freeswitch command into ByteString
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

  /**
    * This function will complete a given promise with Inbound/Outbound FS connection when it receive first command reply from freeswitch
    * else it will timeout after given timeout
    *
    * @param fsConnectionPromise : Promise[FS] promise of FS connection, it will get completed when first command reply received
    * @param fsConnection        FS type of freeswitch connection
    * @param fun                 inject given sink by passing FS connection
    * @param timeout             : FiniteDuration
    * @tparam FS type of FS connection. it must be type of FSConnection
    * @return Sink[List[T], NotUsed]
    */
  def init[FS <: FSConnection](fsConnectionPromise: Promise[FS],
                               fsConnection: FS,
                               fun: (Future[FS]) => Sink[List[FSMessage], _],
                               timeout: FiniteDuration): Sink[List[FSMessage], NotUsed] = {
    var hasAuthenticated = false
    lazy val timeoutFuture = after(duration = timeout, using = system.scheduler) {
      Future.failed(new TimeoutException(s"Socket doesn't receive any response within $timeout."))
    }
    val fsConnectionFuture = Future.firstCompletedOf(Seq(fsConnectionPromise.future, timeoutFuture))
    Flow[List[FSMessage]].map { fsMessages =>
      //TODO Improvement
      if (!hasAuthenticated) {
        val commandReply = fsMessages.collectFirst {
          case command: CommandReply => command
        }
        commandReply.foreach { command =>
          if (command.contentType == ContentTypes.commandReply && command.success) {
            fsConnectionPromise.complete(Success(fsConnection))
            hasAuthenticated = true
          } else {
            fsConnectionPromise.complete(Failure(new Exception(s"Socket failed to make connection with an error: ${command.errorMessage}")))
          }
        }
      }

      fsMessages.collect {
        case command: CommandReply => command
      }.foreach {
        cmdReply =>
          val promise = commandQueue.dequeue()
          if (cmdReply.success)
            promise.tryComplete(Success(cmdReply))
          else promise.tryComplete(Failure(new Exception(s"Failed to get success reply: ${cmdReply.errorMessage}")))
      }
      fsMessages
    }.to(fun(fsConnectionFuture))
  }

  def connect(auth: String): Future[QueueOfferResult]

  def publishCommand(command: FSCommand): Future[CommandReply] = {
    queue.offer(command).flatMap { _ =>
      val promise = Promise[CommandReply]()
      commandQueue.enqueue(promise)
      promise.future
    }
  }

  /**
    * This will publish the `play` command to freeswitch
    *
    * @param fileName : String name of the play file
    * @param config   : ApplicationCommandConfig
    * @return CommandRequest
    */
  def play(fileName: String, config: ApplicationCommandConfig = ApplicationCommandConfig()): Future[CommandReply] =
    publishCommand(PlayFile(fileName, config))

  /**
    * This will publish the `transfer` command to freeswitch
    *
    * @param extension : String
    * @param config    : ApplicationCommandConfig command configuration
    * @return CommandRequest
    */
  def transfer(extension: String, config: ApplicationCommandConfig = ApplicationCommandConfig()): Future[CommandReply] =
    publishCommand(TransferTo(extension, config))

  /**
    * This will publish the `hangup` command to freeswitch
    *
    * @param config :ApplicationCommandConfig
    * @return CommandRequest
    */
  def hangup(config: ApplicationCommandConfig = ApplicationCommandConfig()): Future[CommandReply] =
    publishCommand(Hangup(config))

  /**
    * This will publish the `break` command to freeswitch
    *
    * @param config : ApplicationCommandConfig
    * @return CommandRequest
    */
  def break(config: ApplicationCommandConfig = ApplicationCommandConfig()): Future[CommandReply] =
    publishCommand(Break(config))

  /**
    * This will publish the FS command(play,transfer,break etc) to freeswitch
    *
    * @param command : FSCommand
    * @return CommandRequest
    */
  def sendCommand(command: FSCommand): Future[CommandReply] = publishCommand(command)

  /**
    * This will publish the string version of FS command to freeswitch
    *
    * @param command :String
    * @return CommandRequest
    */
  def sendCommand(command: String): CommandRequest = {
    val commandAsString = CommandAsString(command)
    CommandRequest(commandAsString, queue.offer(commandAsString))
  }

  /**
    * filter
    * Specify event types to listen for. Note, this is not a filter out but rather a "filter in,
    * " that is, when a filter is applied only the filtered values are received. Multiple filters on a socket connection are allowed.
    * Usage:
    * filter <EventHeader> <ValueToFilter>
    *
    * @param events : Map[EventName, String] mapping of events and their value
    * @param config : ApplicationCommandConfig
    * @return
    */
  def filter(events: Map[EventName, String],
             config: ApplicationCommandConfig = ApplicationCommandConfig()): Future[CommandReply] =
    publishCommand(Filter(events, config))

  /**
    * filter delete
    * Specify the events which you want to revoke the filter. filter delete can be used when some filters are applied wrongly or
    * when there is no use of the filter.
    * Usage:
    * filter delete <EventHeader> <ValueToFilter>
    *
    * @param events :Map[EventName, String] mapping of events and their value
    * @param config :ApplicationCommandConfig
    * @return
    */
  def deleteFilter(events: Map[EventName, String],
                   config: ApplicationCommandConfig = ApplicationCommandConfig()): Future[CommandReply] =
    publishCommand(DeleteFilter(events, config))

  /**
    * att_xfer <channel_url>
    * Bridge a third party specified by channel_url onto the call, speak privately, then bridge original caller to target channel_url of att_xfer.
    *
    * @param destination   : String target channel_url of att_xfer
    * @param conferenceKey : Char "attxfer_conf_key" - can be used to initiate a three way transfer (deafault '0')
    * @param hangupKey     : Char "attxfer_hangup_key" - can be used to hangup the call after user wants to end his or her call (deafault '*')
    * @param cancelKey     : Char "attxfer_cancel_key" - can be used to cancel a tranfer just like origination_cancel_key, but straight from the att_xfer code (deafault '#')
    * @param config        : ApplicationCommandConfig
    * @return
    */
  def attXfer(destination: String,
              conferenceKey: Char = '0',
              hangupKey: Char = '*',
              cancelKey: Char = '#',
              config: ApplicationCommandConfig = ApplicationCommandConfig()): Future[CommandReply] =
    publishCommand(AttXfer(destination, conferenceKey, hangupKey, cancelKey, config))

  /**
    * To dial multiple contacts all at once:
    * <action application="bridge" data="sofia/internal/1010@sip.yourprovider.com,sofia/sip/1011@sip.yourprovider.com"/>
    * To dial multiple contacts one at a time:
    * <action application="bridge" data="sofia/internal/1010@sip.yourprovider.com|sofia/sip/1011@sip.yourprovider.com"/>
    *
    * @param targets  :List[String] list of an external SIP address or termination provider
    * @param dialType : DialType To dial multiple contacts all at once then separate targets by comma(,) or To dial multiple contacts one at a time
    *                 then separate targets by pipe(|)
    * @param config   :ApplicationCommandConfig
    * @return
    */
  def bridge(targets: List[String],
             dialType: DialType,
             config: ApplicationCommandConfig = ApplicationCommandConfig()): Future[CommandReply] =
    publishCommand(Bridge(targets, dialType, config))

  /**
    * Allows one channel to bridge itself to the a or b leg of another call. The remaining leg of the original call gets hungup
    * Usage: intercept [-bleg] <uuid>
    *
    * @param uuid   : String
    * @param config :ApplicationCommandConfig
    * @return
    */
  def intercept(uuid: String, config: ApplicationCommandConfig = ApplicationCommandConfig()): Future[CommandReply] =
    publishCommand(Intercept(uuid, config))

  /**
    * Read DTMF (touch-tone) digits.
    * Usage
    * read <min> <max> <sound file> <variable name> <timeout> <terminators>
    *
    * @param min          Minimum number of digits to fetch.
    * @param max          Maximum number of digits to fetch.
    * @param soundFile    Sound file to play before digits are fetched.
    * @param variableName Channel variable that digits should be placed in.
    * @param timeout      Number of milliseconds to wait on each digit
    * @param terminators  Digits used to end input if less than <min> digits have been pressed. (Typically '#')
    * @param config       ApplicationCommandConfig
    */
  def read(min: Int, max: Int)(soundFile: String,
                               variableName: String,
                               timeout: Duration,
                               terminators: List[Char] = List('#'),
                               config: ApplicationCommandConfig = ApplicationCommandConfig()): Future[CommandReply] =
    publishCommand(Read(ReadParameters(min, max, soundFile, variableName, timeout, terminators), config))


}
