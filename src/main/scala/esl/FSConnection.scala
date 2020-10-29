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
import akka.stream._
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Sink, Source}
import akka.util.ByteString
import esl.FSConnection.{FSData, _}
import esl.domain.CallCommands._
import esl.domain.EventNames.EventName
import esl.domain.HangupCauses.HangupCause
import esl.domain.{ApplicationCommandConfig, FSMessage, _}
import esl.parser.{DefaultParser, Parser}

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future, Promise, TimeoutException}
import scala.util.{Failure, Success}
import java.util.UUID

import akka.event.{LogMarker, LoggingAdapter}
import com.typesafe.scalalogging.LazyLogging

abstract class FSConnection extends LazyLogging {
  self =>
  lazy private[this] val parser: Parser = DefaultParser
  implicit protected val system: ActorSystem
  implicit protected val materializer: Materializer
  implicit protected val adapter: LoggingAdapter
  lazy implicit protected val ec: ExecutionContextExecutor = system.dispatcher


  private[this] var connectionId: String = UUID.randomUUID().toString

  def getConnectionId: String = connectionId

  /**
    * This queue maintain the promise of CommandReply for each respective FSCommand
    */
  private[this] val commandQueue: mutable.Queue[Promise[CommandReply]] =
    mutable.Queue.empty

  private[this] val eventMap: mutable.Map[String, CommandToQueue] =
    mutable.Map.empty

  protected[this] lazy val (queue, source) = Source
    .queue[FSCommand](50, OverflowStrategy.backpressure)
    .logWithMarker(name = "esl-queue-stream", e => LogMarker(name = "esl-queue-stream", properties = Map("element" -> e, "connection" -> connectionId)))
    .addAttributes(
      Attributes.logLevels(
        onElement = Attributes.LogLevels.Info,
        onFinish = Attributes.LogLevels.Info,
        onFailure = Attributes.LogLevels.Error))
    .toMat(Sink.asPublisher(false))(Keep.both)
    .run()

  /**
    * It will parsed incoming packet into free switch messages. If there is an unparsed packet from last received packets,
    * will append to the next received packets. So we will get the complete parsed packet.
    */
  private[this] val upstreamFlow: Flow[ByteString, FSData, _] =
    Flow[ByteString].statefulMapConcat(() => {
      var unParsedBuffer: String = ""
      data => {
        val fsPacket = data.utf8String
        logger.debug(s"Connection Id: $getConnectionId : Unparsed data before FS Data: $unParsedBuffer, Received FS Data: $fsPacket")
        val (messages, buffer) = parser.parse(unParsedBuffer + fsPacket)
        unParsedBuffer = buffer
        logger.debug(s"Connection Id: $getConnectionId : Unparsed data After FS Data parsing: $unParsedBuffer")
        List(FSData(self, messages))
      }
    })

  /**
    * It will convert the FS command into ByteString
    */
  private[this] val downstreamFlow: Flow[FSCommand, ByteString, _] =
    Flow.fromFunction { fsCommand =>
      ByteString(fsCommand.toString)
    }

  /**
    * handler() function will create pipeline for source and flow
    *
    * @return Source[FSCommand, NotUsed], BidiFlow[ByteString, List[FSMessage], FSCommand, ByteString, NotUsed]
    *         tuple of source and flow
    */
  def handler(): (
    Source[FSCommand, NotUsed],
      BidiFlow[ByteString, FSData, FSCommand, ByteString, NotUsed]
    ) = {
    (
      Source.fromPublisher(source),
      BidiFlow.fromFlows(upstreamFlow, downstreamFlow)
    )
  }

  /**
    * This function will complete a given promise with Inbound/Outbound FS connection when it receive first command reply from freeswitch
    * else it will timeout after given timeout
    *
    * @param fsConnectionPromise : Promise[FS] promise of FS connection, it will get complete when first success command reply received
    * @param fsConnection        FS type of freeswitch connection
    * @param fun                 inject given sink by passing FS connection
    * @param timeout             : FiniteDuration
    * @tparam FS type of FS connection. it must be type of FSConnection
    * @return Sink[List[T], NotUsed]
    */
  def init[FS <: FSConnection, Mat](
                                     fsConnectionPromise: Promise[FSSocket[FS]],
                                     fsConnection: FS,
                                     fun: Future[FSSocket[FS]] => Sink[FSData, Mat],
                                     timeout: FiniteDuration,
                                     lingering: Boolean
                                   ) = {
    lazy val timeoutFuture =
      after(duration = timeout, using = system.scheduler) {
        Future.failed(
          new TimeoutException(
            s"Socket doesn't receive any response within $timeout."
          )
        )
      }

    lazy val futureSink = Sink.futureSink(
      Future
        .firstCompletedOf(Seq(fsConnectionPromise.future, timeoutFuture))
        .map(fsSocket => fun(Future.successful(fsSocket)))
        .transform(_ match {
          case failure@Failure(ex) =>
            queue.complete()
            failure
          case success => success
        })
    )
    lazy val connectToFS = (fsData: FSData, hasConnected:Boolean) => {
      fsData.fsMessages match {
        case ::(command: CommandReply, _) =>
          if (command.success) {
            fsConnectionPromise.complete(
              Success(FSSocket(fsConnection, ChannelData(command.headers)))
            )
            if (lingering) publishNonMappingCommand(LingerCommand)
            (fsData.copy(fsMessages = fsData.fsMessages.dropWhile(_ == command)),true)
          } else {
            fsConnectionPromise.complete(
              Failure(
                new Exception(
                  s"Socket failed to make connection with an error: ${command.errorMessage}"
                )
              )
            )
            (fsData, hasConnected)
          }
        case _ => (fsData, hasConnected)
      }
    }

    lazy val doLinger = (fsData: FSData, isLingering:Boolean) => {
      fsData.fsMessages match {
        case ::(command: CommandReply, _) =>
          logger.debug(s"Reply of linger command, ${isLingering}, ${command}, promise status: ${fsConnectionPromise.isCompleted}")
          if (command.success && command.replyText.getOrElse("") == "+OK will linger") {
            (fsData.copy(fsMessages = fsData.fsMessages.dropWhile(_ == command)), true)
          } else {
            fsConnectionPromise.complete(
              Failure(
                new Exception(
                  s"Socket failed to linger: ${command.errorMessage}"
                )
              )
            )
            (fsData, isLingering)
          }
        case _ => (fsData, isLingering)
      }
    }

    val containsCmdReply = (fSData: FSData) => {
      fSData.fsMessages.count(a => a.contentType == ContentTypes.commandReply) > 0
    }

    Flow[FSData].statefulMapConcat( () => {
      var hasConnected: Boolean = false
      var isLingering: Boolean = false
      fSData => {
        val updatedFSData = if (containsCmdReply(fSData) && !hasConnected) {
          val con = connectToFS(fSData,hasConnected)
          hasConnected=con._2
          con._1
        }
        else if (containsCmdReply(fSData) && lingering && !isLingering && hasConnected) {
          val ling = doLinger(fSData,isLingering)
          isLingering=ling._2
          ling._1
        }
        else fSData
        //Send every message
        List(updatedFSData.copy(fsMessages = fSData.fsMessages.map(f => handleFSMessage(f))))
      }
    }).logWithMarker(name = "esl-main", e => LogMarker(name = "esl-main", properties = Map("element" -> e, "connection" -> fsConnection.getConnectionId)))
      .addAttributes(
        Attributes.logLevels(
          onElement = Attributes.LogLevels.Info,
          onFinish = Attributes.LogLevels.Info,
          onFailure = Attributes.LogLevels.Error))
      .toMat(futureSink)(Keep.right)

  }

  /**
    * This function will handle each FS message type
    *
    * @param fSMessage : FSMessage FS message
    * @return FSMessage
    */
  private def handleFSMessage(fSMessage: FSMessage): FSMessage =
    fSMessage match {
      case cmdReply: CommandReply => handleCommandReplyMessage(cmdReply)
      case apiResponse: ApiResponse =>
        apiResponse //TODO Will implement logic for handle an api response
      case eventMessage: EventMessage => handleFSEventMessage(eventMessage)
      case basicMessage: BasicMessage =>
        basicMessage //TODO Will implement logic for handle the basic message
    }

  /**
    * This function dequeue an element from queue and complete promise with command reply message
    *
    * @param cmdReply : CommandReply
    * @return CommandReply
    */
  private def handleCommandReplyMessage(
                                         cmdReply: CommandReply
                                       ): CommandReply = {
    if (commandQueue.nonEmpty) {
      val promise = commandQueue.dequeue()
      if (cmdReply.success)
        promise.complete(Success(cmdReply))
      else
        promise.complete(
          Failure(
            new Exception(
              s"Failed to get success reply: ${cmdReply.errorMessage}"
            )
          )
        )
    }
    cmdReply
  }

  /**
    * This will get an element from `eventMap` map by event's uuid
    * Complete promises after receiving `CHANNEL_EXECUTE` and `CHANNEL_EXECUTE_COMPLETE` events
    *
    * @param eventMessage : EventMessage
    * @return EventMessage
    */
  private def handleFSEventMessage(eventMessage: EventMessage): EventMessage = {
    eventMessage.applicationUuid.flatMap(eventMap.lift).foreach {
      commandToQueue =>
        if (eventMessage.eventName.contains(EventNames.ChannelExecute))
          commandToQueue.executeEvent.complete(Success(eventMessage))
        else if (
          eventMessage.eventName.contains(EventNames.ChannelExecuteComplete)
        ) {
          commandToQueue.executeComplete.complete(Success(eventMessage))
          eventMap.remove(commandToQueue.command.eventUuid)
        }
    }
    eventMessage
  }

  /**
    * This function will push a command to source queue and doesn't have any mapping with this command.
    * It is used to sending `connect` or `auth` command
    *
    * @param command : FSCommand
    * @return Future[QueueOfferResult]
    */
  protected def publishNonMappingCommand(
                                          command: FSCommand
                                        ): Future[QueueOfferResult] = queue.offer(command)

  /**
    * publish FS command to FS, An enqueue command into `commandQueue` and add command with events promises into `eventMap`
    *
    * @param command : FSCommand FS command
    * @return Future[CommandResponse]
    */
  private def publishCommand(command: FSCommand): Future[CommandResponse] = {
    queue.offer(command).map { _ =>
      val (commandReply, commandToQueue, cmdResponse) =
        buildCommandAndResponse(command)
      commandQueue.enqueue(commandReply)
      eventMap += command.eventUuid -> commandToQueue
      cmdResponse
    }
  }

  /**
    * This will publish the `play` command to FS
    *
    * @param fileName : String name of the play file
    * @param config   : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def play(
            fileName: String,
            config: ApplicationCommandConfig = ApplicationCommandConfig()
          ): Future[CommandResponse] =
    publishCommand(PlayFile(fileName, config))

  /**
    * Immediately transfer the calling channel to a new context. If there happens to be an xml extension named <destination_number>
    * then control is "warped" directly to that extension. Otherwise it goes through the entire context checking for a match.
    *
    * @param extension : String extension name
    * @param config    : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def transfer(
                extension: String,
                config: ApplicationCommandConfig = ApplicationCommandConfig()
              ): Future[CommandResponse] =
    publishCommand(TransferTo(extension, config))

  /**
    * Hangs up a channel, with an optional cause code supplied.
    * Usage: <action application="hangup" data="USER_BUSY"/>
    *
    * @param cause  : HangupCause
    * @param config :ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def hangup(
              cause: Option[HangupCause] = Option.empty,
              config: ApplicationCommandConfig = ApplicationCommandConfig()
            ): Future[CommandResponse] =
    publishCommand(Hangup(cause, config))

  /**
    * Cancel an application currently running on the channel.Dialplan execution proceeds to the next application.
    * Optionally clears all unprocessed events (queued applications) on the channel.
    *
    * @param config : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def break(
             config: ApplicationCommandConfig = ApplicationCommandConfig()
           ): Future[CommandResponse] =
    publishCommand(Break(config))

  /**
    * Answer the call for a channel.This sets up duplex audio between the calling ''A'' leg and the FreeSwitch server.
    * It is not about other endpoints. The server might need to 'answer' a call to play an audio file or to receive DTMF from the call.
    * Once answered, calls can still be bridged to other extensions. Because a bridge after an answer is actually a transfer,
    * the ringback tones sent to the caller will be defined by transfer_ringback.
    *
    * @param config : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def answer(
              config: ApplicationCommandConfig = ApplicationCommandConfig()
            ): Future[CommandResponse] =
    publishCommand(Answer(config))

  /**
    * This will publish the FS command(play,transfer,break etc) to FS
    *
    * @param command : FSCommand
    * @return Future[CommandResponse]
    */
  def sendCommand(command: FSCommand): Future[CommandResponse] =
    publishCommand(command)

  /**
    * This will publish the string version of FS command to FS
    *
    * @param command   :String
    * @param eventUuid String
    * @return Future[CommandResponse]
    */
  def sendCommand(command: String, eventUuid: String): Future[CommandResponse] =
    publishCommand(CommandAsString(command, eventUuid))

  /**
    * filter
    * Specify event types to listen for. Note, this is not a filter out but rather a "filter in,
    * " that is, when a filter is applied only the filtered values are received. Multiple filters on a socket connection are allowed.
    * Usage:
    * filter <EventHeader> <ValueToFilter>
    *
    * @param events : Map[EventName, String] mapping of events and their value
    * @param config : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def filter(
              events: Map[EventName, String],
              config: ApplicationCommandConfig = ApplicationCommandConfig()
            ): Future[CommandResponse] =
    publishCommand(Filter(events, config))

  /**
    * filterUUId
    * Specify events of specific channel UUId to listen for. Note, this is not a filter out but rather a "filter in," that is,
    * when a filter is applied only the filtered values are received. Multiple filters on a socket connection are allowed.
    * Usage:
    * filter <Unique-ID> <uuid>
    *
    * @param uuid   : Channel uuid to filter in
    * @param config : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def filterUUId(
                  uuid: String,
                  config: ApplicationCommandConfig = ApplicationCommandConfig()
                ): Future[CommandResponse] =
    publishCommand(FilterUUId(uuid, config))

  /**
    * filter delete
    * Specify the events which you want to revoke the filter. filter delete can be used when some filters are applied wrongly or
    * when there is no use of the filter.
    * Usage:
    * filter delete <EventHeader> <ValueToFilter>
    *
    * @param events :Map[EventName, String] mapping of events and their value
    * @param config :ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def deleteFilter(
                    events: Map[EventName, String],
                    config: ApplicationCommandConfig = ApplicationCommandConfig()
                  ): Future[CommandResponse] =
    publishCommand(DeleteFilter(events, config))

  /**
    * filterUUId delete
    * Specify channel UUId to revoke the filter. filter delete can be used when some filters are applied wrongly or
    * when there is no use of the filter.
    * Usage:
    * filter delete <Unique-ID> <uuid>
    *
    * @param uuid   : Channel uuid to filter out
    * @param config :ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def deleteUUIdFilter(
                        uuid: String,
                        config: ApplicationCommandConfig = ApplicationCommandConfig()
                      ): Future[CommandResponse] =
    publishCommand(DeleteUUIdFilter(uuid, config))

  /**
    * att_xfer <channel_url>
    * Bridge a third party specified by channel_url onto the call, speak privately, then bridge original caller to target channel_url of att_xfer.
    *
    * @param destination   : String target channel_url of att_xfer
    * @param conferenceKey : Char "attxfer_conf_key" - can be used to initiate a three way transfer (deafault '0')
    * @param hangupKey     : Char "attxfer_hangup_key" - can be used to hangup the call after user wants to end his or her call (deafault '*')
    * @param cancelKey     : Char "attxfer_cancel_key" - can be used to cancel a tranfer just like origination_cancel_key, but straight from the att_xfer code (deafault '#')
    * @param config        : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def attXfer(
               destination: String,
               conferenceKey: Char = '0',
               hangupKey: Char = '*',
               cancelKey: Char = '#',
               config: ApplicationCommandConfig = ApplicationCommandConfig()
             ): Future[CommandResponse] =
    publishCommand(
      AttXfer(destination, conferenceKey, hangupKey, cancelKey, config)
    )

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
    * @return Future[CommandResponse]
    */
  def bridge(
              targets: List[String],
              dialType: DialType,
              config: ApplicationCommandConfig = ApplicationCommandConfig()
            ): Future[CommandResponse] =
    publishCommand(Bridge(targets, dialType, config))

  /**
    * Allows one channel to bridge itself to the a or b leg of another call. The remaining leg of the original call gets hungup
    * Usage: intercept [-bleg] <uuid>
    *
    * @param uuid   : String
    * @param config :ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def intercept(
                 uuid: String,
                 config: ApplicationCommandConfig = ApplicationCommandConfig()
               ): Future[CommandResponse] =
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
    * @return Future[CommandResponse]
    */
  def read(min: Int, max: Int)(
    soundFile: String,
    variableName: String,
    timeout: Duration,
    terminators: List[Char] = List('#'),
    config: ApplicationCommandConfig = ApplicationCommandConfig()
  ): Future[CommandResponse] =
    publishCommand(
      Read(
        ReadParameters(min, max, soundFile, variableName, timeout, terminators),
        config
      )
    )

  /**
    * Subscribe for `myevents` with `uuid`
    * The 'myevents' subscription allows your inbound socket connection to behave like an outbound socket connect.
    * It will "lock on" to the events for a particular uuid and will ignore all other events, closing the socket
    * when the channel goes away or closing the channel when the socket disconnects and all applications have finished executing.
    * For outbound, no need to send UUID. FS already knows the uuid of call so it automatically uses that
    *
    * @param uuid : String
    * @return Future[CommandReply
    */
  def subscribeMyEvents(uuid: String = ""): Future[CommandResponse] =
    publishCommand(SubscribeMyEvents(uuid))

  /**
    * Enable or disable events by class or all (plain or xml or json output format). Currently we are supporting plain events
    *
    * @param events : EventName* specify any number of events
    * @return Future[CommandReply
    */
  def subscribeEvents(events: EventName*): Future[CommandResponse] =
    publishCommand(SubscribeEvents(events.toList))

  /**
    * Pause the channel for a given number of milliseconds, consuming the audio for that period of time.
    * Calling sleep also will consume any outstanding RTP on the operating system's input queue,
    * which can be very useful in situations where audio becomes backlogged.
    * To consume DTMFs, use the sleep_eat_digits variable.
    * Usage: <action application="sleep" data=<milliseconds>/>
    *
    * @param numberOfMillis : Duration duration must be in milliseconds
    * @param config         : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def sleep(
             numberOfMillis: Duration,
             config: ApplicationCommandConfig = ApplicationCommandConfig()
           ): Future[CommandResponse] =
    publishCommand(Sleep(numberOfMillis, config))

  /**
    * Set a channel variable for the channel calling the application.
    * Usage set <channel_variable>=<value>
    *
    * @param varName  : String variable name
    * @param varValue : String variable value
    * @param config   : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def setVar(
              varName: String,
              varValue: String,
              config: ApplicationCommandConfig = ApplicationCommandConfig()
            ): Future[CommandResponse] =
    publishCommand(SetVar(varName, varValue, config))

  /**
    * pre_answer establishes media (early media) but does not answer.
    *
    * @param config : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def preAnswer(
                 config: ApplicationCommandConfig = ApplicationCommandConfig()
               ): Future[CommandResponse] =
    publishCommand(PreAnswer(config))

  /**
    * Record to a file from the channel's input media stream
    * Record is used to record voice messages, such as in a voicemail system. This application will record to a file specified by <path>.
    * After recording stops the record app sets the following read-only variables:
    *
    * record_ms — duration of most recently recorded file in milliseconds
    * record_samples — number of recorded samples
    * playback_terminator_used — TouchTone digit used to terminate recording
    *
    * @param filePath      : String An application will record to a file specified by file path.
    * @param timeLimitSecs : Duration it is the maximum duration of the recording in seconds
    * @param silenceThresh : Duration it is an energy level below which is considered silence.
    * @param silenceHits   : Duration it is how many seconds of audio below silence_thresh will be tolerated before the recording stops.
    *                      When omitted, the default value is 3 seconds
    * @param config        :ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def record(
              filePath: String,
              timeLimitSecs: Duration,
              silenceThresh: Duration,
              silenceHits: Option[Duration] = Option.empty,
              config: ApplicationCommandConfig = ApplicationCommandConfig()
            ): Future[CommandResponse] =
    publishCommand(
      Record(filePath, timeLimitSecs, silenceThresh, silenceHits, config)
    )

  /**
    * Records an entire phone call or session.
    * Multiple media bugs can be placed on the same channel.
    *
    * @param filePathWithFormat : String file path with format like test.gsm,test.mp3,test.wav,test.ogg etc
    * @param config             : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def recordSession(
                     filePathWithFormat: String,
                     config: ApplicationCommandConfig = ApplicationCommandConfig()
                   ): Future[CommandResponse] =
    publishCommand(RecordSession(filePathWithFormat, config))

  /**
    * Send DTMF digits from the session using the method(s) configured on the endpoint in use
    * If no duration is specified the default DTMF length of 2000ms will be used.
    *
    * @param dtmfDigits   : String DTMF digits
    * @param toneDuration : Option[Duration] default is empty
    * @param config       : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def sendDtmf(
                dtmfDigits: String,
                toneDuration: Option[Duration] = Option.empty,
                config: ApplicationCommandConfig = ApplicationCommandConfig()
              ): Future[CommandResponse] =
    publishCommand(SendDtmf(dtmfDigits, toneDuration, config))

  /**
    * Stop record session.
    * Usage: <action application="stop_record_session" data="path"/>
    *
    * @param filePath : String file name
    * @param config   : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def stopRecordSession(
                         filePath: String,
                         config: ApplicationCommandConfig = ApplicationCommandConfig()
                       ): Future[CommandResponse] =
    publishCommand(StopRecordSession(filePath, config))

  /**
    * Places a channel "on hold" in the switch, instead of in the phone. Allows for a number of different options, including:
    * Set caller in a place where the channel won't be hungup on, while waiting for someone to talk to.
    * Generic "hold" mechanism, where you transfer a caller to it.
    * Please note that to retrieve a call that has been "parked", you'll have to bridge to them or transfer the call to a valid location.
    * Also, remember that parking a call does NOT supply music on hold or any other media.
    * Park is quite literally a way to put a call in limbo until you you bridge/uuid_bridge or transfer/uuid_transfer it.
    * Note that the park application takes no arguments, so the data attribute can be omitted in the definition.
    *
    * @param config : ApplicationCommandConfig
    * @return Future[CommandResponse]
    */
  def park(
            config: ApplicationCommandConfig = ApplicationCommandConfig()
          ): Future[CommandResponse] =
    publishCommand(Park(config))

  /**
    * Enable log output. Levels same as the console.conf values
    *
    * @param logLevel : String
    * @param config   : ApplicationCommandConfig
    */
  def log(
           logLevel: String,
           config: ApplicationCommandConfig = ApplicationCommandConfig()
         ): Future[CommandResponse] =
    publishCommand(Log(logLevel, config))

  /**
    * Close the socket connection.
    *
    * @param config : ApplicationCommandConfig
    */
  def exit(
            config: ApplicationCommandConfig = ApplicationCommandConfig()
          ): Future[CommandResponse] =
    publishCommand(Exit(config))
}

object FSConnection {

  private type CommandBuilder =
    (Promise[CommandReply], CommandToQueue, CommandResponse)

  /**
    * This is FS command and its events promises
    *
    * @param command         : FSCommand it is FS command
    * @param executeEvent    : Promise[EventMessage] this has to be complete when socket receive ChannelExecute event
    * @param executeComplete : Promise[EventMessage] this has to be complete when socket receive ChannelExecuteComplete event
    */
  case class CommandToQueue(
                             command: FSCommand,
                             executeEvent: Promise[EventMessage],
                             executeComplete: Promise[EventMessage]
                           )

  /**
    * It is the response of FS command that has command and events Futures
    *
    * @param command         : FSCommand
    * @param commandReply    : Future[CommandReply] this is the reply of FS command
    * @param executeEvent    : Future[EventMessage] this is the ChannelExecute event response of FS command
    * @param executeComplete : Future[EventMessage] this is the ChannelExecuteComplete event response of FS command
    */
  case class CommandResponse(
                              command: FSCommand,
                              commandReply: Future[CommandReply],
                              executeEvent: Future[EventMessage],
                              executeComplete: Future[EventMessage]
                            )

  /**
    *
    * @param command : FSCommand FS command
    * @return CommandBuilder it is the tuple of three elements. First element of tuple is Promise[CommandReply],
    *         Second element of tuple is CommandToQueue and third element of tuple is CommandResponse
    *
    */
  private def buildCommandAndResponse(command: FSCommand): CommandBuilder = {
    val commandReply = Promise[CommandReply]()
    val execEvent = Promise[EventMessage]()
    val completeEvent = Promise[EventMessage]()
    (
      commandReply,
      CommandToQueue(command, execEvent, completeEvent),
      CommandResponse(
        command,
        commandReply.future,
        execEvent.future,
        completeEvent.future
      )
    )
  }

  /**
    * FSSocket represent fs inbound/outbound socket connection and connection reply
    *
    * @param fsConnection type of FSConnection connection
    * @param channelData  : ChannelData
    * @tparam FS type of Fs connection, it could be Inbound/Outbound
    */
  case class FSSocket[FS <: FSConnection](
                                           fsConnection: FS,
                                           channelData: ChannelData
                                         )

  case class FSData(fSConnection: FSConnection, fsMessages: List[FSMessage])

  case class ChannelData(headers: Map[String, String]) {
    lazy val uuid: Option[String] = headers.get(HeaderNames.uniqueId)
  }

}
