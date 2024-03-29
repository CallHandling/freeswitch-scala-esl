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

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.pattern.after
import akka.stream._
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, Sink, Source}
import akka.util.ByteString
import esl.FSConnection.{FSData, _}
import esl.domain.CallCommands._
import esl.domain.EventNames.{EventName, events}
import esl.domain.HangupCauses.HangupCause
import esl.domain.{ApplicationCommandConfig, FSMessage, _}
import esl.parser.{DefaultParser, Parser}

import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{
  ExecutionContextExecutor,
  Future,
  Promise,
  TimeoutException
}
import scala.util.{Failure, Success, Try}
import java.util.UUID
import akka.event.{LogMarker, MarkerLoggingAdapter}
import com.typesafe.scalalogging.StrictLogging

import scala.annotation.tailrec

abstract class FSConnection extends StrictLogging {
  self =>
  lazy private[this] val parser: Parser = DefaultParser
  implicit protected val system: ActorSystem
  implicit protected val materializer: Materializer
  implicit protected val adapter: MarkerLoggingAdapter
  lazy implicit protected val ec: ExecutionContextExecutor = system.dispatcher

  private[this] var originatedCallIds: mutable.Set[String] =
    mutable.Set.empty[String]

  def enableDebugLogs: Boolean

  private[this] var connectionId: String =
    "pre-call-id: " + UUID.randomUUID().toString

  def getOriginatedCallIds: mutable.Set[String] = originatedCallIds

  def setOriginatedCallIds(uuid: String): Unit = originatedCallIds.add(uuid)

  def getConnectionId: String = connectionId

  def setConnectionId(connId: String): Unit = connectionId = connId

  private var onCommandCallbacks
      : Seq[PartialFunction[FSCommandPublication, Unit]] =
    Seq.empty

  def onSendCommand(
      partialFunction: PartialFunction[FSCommandPublication, Unit]
  ): Unit = {
    onCommandCallbacks = onCommandCallbacks :+ partialFunction
  }

  def logMarker =
    LogMarker("hubbub-esl-fs", Map("FS-INTERFACE-ID" -> getConnectionId))

  private[this] val killSwitch =
    KillSwitches.shared("kill-" + getConnectionId)

  /**
    * This queue maintain the promise of CommandReply for each respective FSCommand
    */
  private[this] val commandQueue: mutable.Queue[Promise[CommandReply]] =
    mutable.Queue.empty

  private[this] val eventMap
      : scala.collection.concurrent.Map[String, CommandToQueue] =
    scala.collection.concurrent.TrieMap.empty[String, CommandToQueue]

  private[this] def decider(origin: String): Supervision.Decider = {
    case ne: NullPointerException => {
      adapter.error(
        logMarker,
        ne,
        s"NullPointerException in FS Connection Flow @$origin; will Resume"
      )
      Supervision.Resume
    }
    case ex => {
      adapter.error(
        logMarker,
        ex,
        s"Exception in FS Connection Flow @$origin; will Stop"
      )
      Supervision.Stop
    }
  }

  protected[this] lazy val (queue, source) = {
    val sourceStage1 = Source
      .queue[FSCommand](50, OverflowStrategy.fail)

    sourceStage1
      .logWithMarker(
        name = "esl-freeswitch-outstream",
        e =>
          LogMarker(
            name = "esl-freeswitch-outstream",
            properties = Map("element" -> e, "connection" -> connectionId)
          )
      )
      .addAttributes(
        Attributes.logLevels(
          onElement = if (enableDebugLogs) {
            Attributes.LogLevels.Debug
          } else {
            Attributes.LogLevels.Off
          },
          onFinish = Attributes.LogLevels.Info,
          onFailure = Attributes.LogLevels.Error
        )
      )
      .recover {
        case e => {
          adapter.error(logMarker, e.getMessage, e)
          throw e
        }
      }
      .addAttributes(
        ActorAttributes.supervisionStrategy(decider("fs-command-queue"))
      )
      .toMat(Sink.asPublisher(false))(Keep.both)
      .run()
  }

  /**
    * It will parsed incoming packet into free switch messages. If there is an unparsed packet from last received packets,
    * will append to the next received packets. So we will get the complete parsed packet.
    */
  private[this] val downStreamFlow: Flow[ByteString, FSData, _] =
    Flow[ByteString]
      .via(killSwitch.flow)
      .addAttributes(
        ActorAttributes.supervisionStrategy(decider("fs-events-receive"))
      )
      .statefulMapConcat(() => {
        var unParsedBuffer: String = ""
        data => {
          try {
            val fsPacket = data.utf8String
            val (messages, buffer) = parser.parse(unParsedBuffer + fsPacket)
            unParsedBuffer = buffer
            List(FSData(self, messages))
          } catch {
            case e: Exception => {
              adapter.error(logMarker, e.getMessage, e)
              List(FSData(self, List(NoneMessage())))
            }
          }
        }
      })

  /**
    * It will convert the FS command into ByteString
    */
  private[this] val upStreamFlow: Flow[FSCommand, ByteString, _] =
    Flow.fromFunction { fsCommand =>
      val str =
        try { fsCommand.toString }
        catch {
          case e: Throwable =>
            adapter.error(logMarker, e, "Could not convert FSCommand to string")
            "none"
        }
      ByteString(str)
    }

  /**
    * handler() function will create pipeline for source and flow
    *
    * @return (Future[Done], Source[FSCommand, NotUsed], BidiFlow[ByteString, List[FSMessage], FSCommand, ByteString, NotUsed])
    *         triplet of upstream queue completion future, source and flow
    */
  def handler(): (
      Future[Done],
      Source[FSCommand, NotUsed],
      BidiFlow[ByteString, FSData, FSCommand, ByteString, NotUsed]
  ) = {
    (
      queue.watchCompletion(),
      Source
        .fromPublisher(source)
        .addAttributes(
          ActorAttributes.supervisionStrategy(decider("bidi-fs-command"))
        ),
      BidiFlow.fromFlows(
        downStreamFlow
          .addAttributes(
            ActorAttributes.supervisionStrategy(decider("bidi-fs-events"))
          ),
        upStreamFlow
          .addAttributes(
            ActorAttributes.supervisionStrategy(decider("bidi-fs-command-2"))
          )
      )
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
      callId: String,
      fsConnectionPromise: Promise[FSSocket[FS]],
      fsConnection: FS,
      fun: (String, Future[FSSocket[FS]]) => Sink[FSData, Mat],
      timeout: FiniteDuration,
      needToLinger: Boolean
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
        .map(fsSocket => {
          fun(callId, Future.successful(fsSocket))
        })
        .transform({
          case failure @ Failure(ex) =>
            adapter.error(
              logMarker,
              ex,
              s"CALL ${callId} About to shutdown due to exception in Future sink"
            )
            kill
            failure
          case success => success
        })
    )
    lazy val connectToFS = (fsData: FSData, hasConnected: Boolean) => {
      fsData.fsMessages match {
        case ::(command: CommandReply, _)
            if (command.headers.contains(HeaderNames.uniqueId)) =>
          if (command.success) {
            val fsSocket = FSSocket(fsConnection, ChannelData(command.headers))
            fsSocket.fsConnection.setConnectionId(
              fsSocket.channelData.headers(HeaderNames.uniqueId)
            )
            fsConnectionPromise.complete(
              Success(fsSocket)
            )
            if (needToLinger) publishNonMappingCommand(LingerCommand)
            (
              fsData
                .copy(fsMessages = fsData.fsMessages.dropWhile(_ == command)),
              true
            )
          } else {
            adapter.error(
              logMarker,
              s"CALL ${callId}Socket failed to make connection with an error: ${command.errorMessage}"
            )
            fsConnectionPromise.complete(
              Failure(
                new Exception(
                  s"CALL ${callId}Socket failed to make connection with an error: ${command.errorMessage}"
                )
              )
            )
            (fsData, hasConnected)
          }
        case _ => (fsData, hasConnected)
      }
    }

    lazy val doLinger = (fsData: FSData, isLingering: Boolean) => {
      fsData.fsMessages match {
        case ::(command: CommandReply, _) =>
          adapter.info(
            logMarker,
            s"CALL ${callId} Reply of linger command, ${isLingering}, ${command}, promise status: ${fsConnectionPromise.isCompleted}"
          )
          if (command.success) {
            (
              fsData
                .copy(fsMessages = fsData.fsMessages.dropWhile(_ == command)),
              true,
              Some(command)
            )
          } else {
            (fsData, isLingering, Option.empty)
          }
        case _ => (fsData, isLingering, Option.empty)
      }
    }

    val getCmdReplies = (fSData: FSData) => {
      fSData.fsMessages.filter(a => a.contentType == ContentTypes.commandReply)
    }

    def freeSwitchMsgToString(msg: FSMessage): String = {
      val space = "    "
      s""">> TYPE
         |$space${msg.getClass.getSimpleName} ${msg.headers.getOrElse(
        HeaderNames.eventName,
        "NA"
      )}
         |>> HEADERS
         |${msg.headers
        .map(h => h._1 + " : " + h._2)
        .mkString(space, "\n" + space, "")}
         |>> BODY
         |$space${msg.body.getOrElse("NA")}""".stripMargin
    }

    def isEventAllowedForID(uniqueIdHeaderValue: String) = {
      uniqueIdHeaderValue == getConnectionId || getOriginatedCallIds
        .contains(uniqueIdHeaderValue)
    }

    Flow[FSData]
      .statefulMapConcat(() => {
        var hasConnected: Boolean = false
        var isLingering: Boolean = false
        var isFiltering: Boolean = false
        val buffer: mutable.ListBuffer[FSData] =
          mutable.ListBuffer.empty[FSData]

        @tailrec
        def filterFSMessages(fSData: FSData): FSData = {

          val (messagesWithSameId, messagesWithDifferentId) = {
            fSData.fsMessages.partition { message =>
              message.headers
                .get(HeaderNames.uniqueId)
                .fold(true)(isEventAllowedForID)
            }
          }
          if (messagesWithDifferentId.nonEmpty) {
            val relatedChannels = messagesWithDifferentId.flatMap({
              case e: EventMessage
                  if e.eventName.exists(
                    _.name == EventNames.ChannelOriginate.name
                  ) && e.channelCallUUID
                    .exists(isEventAllowedForID) && e.headers
                    .get(HeaderNames.CallerContext)
                    .fold(false)(_ == "transfer_handler") && e.headers
                    .get(HeaderNames.originatorChannel)
                    .exists(isEventAllowedForID) => {
                e.uuid.filterNot(isEventAllowedForID).toList
              }
              case _ => Nil
            })

            if (relatedChannels.nonEmpty) {
              relatedChannels.distinct.foreach(setOriginatedCallIds)
              filterFSMessages(fSData)
            } else {
              adapter.warning(
                logMarker,
                s"""CALL $callId FS-INTERFACE-ID $getConnectionId $connectionId socket has received ${messagesWithDifferentId.length} message(s) from other calls
                   |getOriginatedCallIds = ${getOriginatedCallIds
                  .mkString("[", ",", "]")}
                   |other call ids ${messagesWithDifferentId
                  .map(_.headers(HeaderNames.uniqueId))
                  .mkString("[", ",", "]")}
                   |
                   |------  messages below -----
                   |${messagesWithDifferentId
                  .map(freeSwitchMsgToString)
                  .mkString("\n----")}""".stripMargin
              )
              fSData.copy(fsMessages = messagesWithSameId)
            }
          } else {
            fSData.copy(fsMessages = messagesWithSameId)
          }
        }

        def fsDataToString(fSData: FSData) = {
          fSData.fsMessages
            .map({
              msg =>
                s"""
                   |----------------------------------------------
                   |>> HEADERS
                   |${msg.headers
                  .map(h => h._1 + " : " + h._2)
                  .mkString(space, "\n" + space, "")}
                   |>> BODY
                   |${msg.body}
                   |
                   |""".stripMargin
            })
            .mkString("")
        }

        fSData => {
          lazy val cmdReplies = getCmdReplies(fSData)
          lazy val isConnect = cmdReplies.foldLeft(false)((_, b) =>
            b.headers.contains(HeaderNames.uniqueId)
          )

          val updatedFSData = {
            if (!hasConnected && !isConnect) {
              buffer.append(fSData)
              adapter.info(
                logMarker,
                s"""fsData received msgs from freeswitch not connected and is not connect so will buffer
                   |${fsDataToString(fSData)}""".stripMargin
              )
              fSData.copy(fsMessages = Nil)
            } else if (!hasConnected && isConnect) {
              val con = connectToFS(fSData, hasConnected)
              hasConnected = con._2
              if (hasConnected) {
                val allMessages = fSData.copy(fsMessages =
                  buffer.flatMap(_.fsMessages).toList ++ fSData.fsMessages
                )
                buffer.clear()
                adapter.info(
                  logMarker,
                  s"""fsData received msgs from freeswitch NOW CONNECTED So will flush buffer""".stripMargin
                )
                filterFSMessages(allMessages)
              } else {
                buffer.append(con._1)
                adapter.info(
                  logMarker,
                  s"""fsData received msgs from freeswitch not connected and is connect but didnt connect so will buffer
                     |${fsDataToString(con._1)}""".stripMargin
                )
                fSData.copy(fsMessages = Nil)
              }
            } else if (
              needToLinger && !isLingering && !isConnect && cmdReplies
                .exists {
                  case a: CommandReply =>
                    a.replyText.getOrElse("") == "+OK will linger"
                }
            ) {
              val ling = doLinger(fSData, isLingering)
              isLingering = ling._2
              ling._3.foreach {
                msg =>
                  adapter.info(
                    logMarker,
                    s"""fsData filtered out linger
                     |>> HEADERS
                     |${msg.headers
                      .map(h => h._1 + " : " + h._2)
                      .mkString(space, "\n" + space, "")}
                     |>> BODY
                     |${msg.body}""".stripMargin
                  )
              }
              filterFSMessages(ling._1)
            } else {
              filterFSMessages(fSData)
            }
          }
          List(
            updatedFSData
              .copy(fsMessages =
                updatedFSData.fsMessages.map(f => handleFSMessage(f))
              )
          )
        }
      })
      .addAttributes(ActorAttributes.supervisionStrategy(decider("fs-init")))
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
      case cmdReply: CommandReply => {
        adapter.info(
          logMarker,
          s"""handleFSEventMessage received CommandReply
             |>> HEADERS
             |${cmdReply.headers
            .map(h => h._1 + " : " + h._2)
            .mkString(space, "\n" + space, "")}
             |>> BODY
             |${cmdReply.body}""".stripMargin
        )
        handleCommandReplyMessage(cmdReply)
      }
      case apiResponse: ApiResponse => {
        adapter.info(
          logMarker,
          s"""handleFSEventMessage received ApiResponse
             |ERROR : ${apiResponse.errorMessage}
             |>> HEADERS
             |${apiResponse.headers
            .map(h => h._1 + " : " + h._2)
            .mkString(space, "\n" + space, "")}
             |>> BODY
             |${apiResponse.body}""".stripMargin
        )
        apiResponse //TODO Will implement logic for handle an api response
      }
      case eventMessage: EventMessage => {
        adapter.info(
          logMarker,
          s"""handleFSEventMessage received EventMessage
             |>> TYPE
             |EVENT ${eventMessage.eventName.getOrElse("NA")}
             |>> HEADERS
             |${eventMessage.headers
            .map(h => h._1 + " : " + h._2)
            .mkString(space, "\n" + space, "")}
             |>> BODY
             |${eventMessage.body}""".stripMargin
        )
        handleFSEventMessage(eventMessage)
      }
      case basicMessage: BasicMessage => {
        adapter.info(
          logMarker,
          s"""handleFSEventMessage received BasicMessage
             |>> HEADERS
             |${basicMessage.headers
            .map(h => h._1 + " : " + h._2)
            .mkString(space, "\n" + space, "")}
             |>> BODY
             |${basicMessage.body}""".stripMargin
        )
        basicMessage //TODO Will implement logic for handle the basic message
      }
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

  private val space = "    "

  /**
    * This will get an element from `eventMap` map by event's uuid
    * Complete promises after receiving `CHANNEL_EXECUTE` and `CHANNEL_EXECUTE_COMPLETE` events
    *
    * @param eventMessage : EventMessage
    * @return EventMessage
    */
  private def handleFSEventMessage(eventMessage: EventMessage): EventMessage = {

    eventMessage.applicationUuid match {
      case Some(appId) => {
        eventMap.get(appId) match {
          case Some(commandToQueue) => {
            if (eventMessage.eventName.contains(EventNames.ChannelExecute)) {
              commandToQueue.executeEvent.complete(Success(eventMessage))
              adapter.info(
                logMarker,
                s"""handleFSEventMessage for app id $appId completed process future
                   |>> TYPE
                   |EVENT ${eventMessage.eventName.getOrElse("NA")}
                   |>> HEADERS
                   |${eventMessage.headers
                  .map(h => h._1 + " : " + h._2)
                  .mkString(space, "\n" + space, "")}
                   |>> BODY
                   |${eventMessage.body}""".stripMargin
              )
            } else if (
              eventMessage.eventName.contains(EventNames.ChannelExecuteComplete)
            ) {
              commandToQueue.command match {
                case bridge: Bridge
                    if !eventMessage.headers.contains(
                      "variable_sip_bye_h_X-CH-ByeReason"
                    ) => {
                  adapter.info(
                    logMarker,
                    s"""handleFSEventMessage for app id $appId skipping as header "variable_sip_bye_h_X-CH-ByeReason" missing
                       |>> TYPE
                       |EVENT ${eventMessage.eventName.getOrElse("NA")}
                       |>> HEADERS
                       |${eventMessage.headers
                      .map(h => h._1 + " : " + h._2)
                      .mkString(space, "\n" + space, "")}
                       |>> BODY
                       |${eventMessage.body}""".stripMargin
                  )
                }
                case _ => {
                  commandToQueue.executeComplete.complete(Success(eventMessage))
                  eventMap.remove(commandToQueue.command.eventUuid)
                  adapter.info(
                    logMarker,
                    s"""handleFSEventMessage for app id $appId Removing entry
                       |>> TYPE
                       |EVENT ${eventMessage.eventName.getOrElse("NA")}
                       |>> HEADERS
                       |${eventMessage.headers
                      .map(h => h._1 + " : " + h._2)
                      .mkString(space, "\n" + space, "")}
                       |>> BODY
                       |${eventMessage.body}
                       |>> MAP is below
                       |${eventMap
                      .map({ item =>
                        s"""appId: ${item._1}
                           |command
                           |${item._2.command}""".stripMargin
                      })
                      .mkString("\n")}""".stripMargin
                  )
                }
              }

            } else {
              adapter.warning(
                logMarker,
                s"""handleFSEventMessage for app id $appId Unable to handle Command (unexpected eventName header)
                   |>> TYPE
                   |EVENT ${eventMessage.eventName.getOrElse("NA")}
                   |>> HEADERS
                   |${eventMessage.headers
                  .map(h => h._1 + " : " + h._2)
                  .mkString(space, "\n" + space, "")}
                   |>> BODY
                   |${eventMessage.body}""".stripMargin
              )
            }
          }
          case _ => {
            adapter.warning(
              logMarker,
              s"""handleFSEventMessage for app id $appId Unable to find Command in look up
                 |>> TYPE
                 |EVENT ${eventMessage.eventName}
                 |>> HEADERS
                 |${eventMessage.headers
                .map(h => h._1 + " : " + h._2)
                .mkString(space, "\n" + space, "")}
                 |>> BODY
                 |${eventMessage.body}""".stripMargin
            )
          }
        }
      }
      case _ => {
        adapter.debug(
          logMarker,
          s"""handleFSEventMessage Unable to find application id header for event message
             |>> TYPE
             |EVENT ${eventMessage.eventName}
             |>> HEADERS
             |${eventMessage.headers
            .map(h => h._1 + " : " + h._2)
            .mkString(space, "\n" + space, "")}
             |>> BODY
             |${eventMessage.body}""".stripMargin
        )
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
  ): Future[QueueOfferResult] = {

    val offerF = queue
      .offer(command)

    if (onCommandCallbacks.nonEmpty) {
      offerF
        .map({ offer =>
          onCommandCallbacks
            .foreach(
              _.lift(FireAndForgetFSCommand(command, Success(offer)))
            )
          offer
        })
        .recoverWith({
          case e => {
            onCommandCallbacks
              .foreach(_.lift(FireAndForgetFSCommand(command, Failure(e))))
            Future.failed(e)
          }
        })
    } else {
      offerF
    }
  }

  /**
    * publish FS command to FS, An enqueue command into `commandQueue` and add command with events promises into `eventMap`
    *
    * @param command : FSCommand FS command
    * @return Future[CommandResponse]
    */
  private def publishCommand(command: FSCommand): Future[CommandResponse] = {
    val offerF = queue
      .offer(command)
      .map({ offer =>
        val (commandReply, commandToQueue, cmdResponse) =
          buildCommandAndResponse(command)
        commandQueue.enqueue(commandReply)
        val appId = command.eventUuid
        eventMap.put(appId, commandToQueue)
        adapter.info(
          logMarker,
          s"""publishCommand for app id $appId Added to lookup
             |>> COMMAND
             |$command
             |>> MAP is now
             |${eventMap
            .map({ item =>
              s"""appId: ${item._1}
               |command
               |${item._2.command}""".stripMargin
            })
            .mkString("\n")}""".stripMargin
        )
        onCommandCallbacks
          .foreach(
            _.lift(
              AwaitingFSCommand(
                command,
                Success(
                  offer -> AwaitFSCommandResponse(
                    cmdResponse.commandReply,
                    cmdResponse.executeEvent,
                    cmdResponse.executeComplete
                  )
                )
              )
            )
          )
        cmdResponse
      })

    if (onCommandCallbacks.nonEmpty) {
      offerF
        .recoverWith({
          case e => {
            onCommandCallbacks
              .foreach(_.lift(AwaitingFSCommand(command, Failure(e))))
            Future.failed(e)
          }
        })
    } else {
      offerF
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
  ): Future[CommandResponse] = {
    setOriginatedCallIds(uuid)
    publishCommand(FilterUUId(uuid, config))
  }

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
  ): Future[CommandResponse] = {
    publishCommand(Exit(config))
  }

  def kill = {
    killSwitch.shutdown()
    queue.complete()
  }
}

object FSConnection {

  sealed trait FSCommandPublication {
    def command: FSCommand
  }

  case class FireAndForgetFSCommand(
      command: FSCommand,
      queueOfferResult: Try[QueueOfferResult]
  ) extends FSCommandPublication

  case class AwaitingFSCommand(
      command: FSCommand,
      result: Try[(QueueOfferResult, AwaitFSCommandResponse)]
  ) extends FSCommandPublication

  case class AwaitFSCommandResponse(
      reply: Future[CommandReply],
      execute: Future[EventMessage],
      complete: Future[EventMessage]
  )

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
  case class FSSocket[+FS <: FSConnection](
      fsConnection: FS,
      channelData: ChannelData
  )

  case class FSData(fSConnection: FSConnection, fsMessages: List[FSMessage])

  case class ChannelData(headers: Map[String, String]) {
    lazy val uuid: Option[String] = headers.get(HeaderNames.uniqueId)
  }

}
