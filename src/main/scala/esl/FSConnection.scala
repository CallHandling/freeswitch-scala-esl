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
import esl.domain.{CommandResponse, _}
import esl.parser.{DefaultParser, Parser}

import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{
  Await,
  ExecutionContextExecutor,
  Future,
  Promise,
  TimeoutException
}
import scala.util.{Failure, Success, Try}
import java.util.UUID
import akka.event.{LogMarker, MarkerLoggingAdapter}
import com.typesafe.scalalogging.StrictLogging
import esl.domain.CallCommands.ConferenceCommand.SendConferenceCommand
import esl.domain.CallCommands.Dial.DialConfig

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

  private var onFsMsgCallbacks
      : Seq[PartialFunction[(List[FSMessage], String, List[FSMessage]), Unit]] =
    Seq.empty

  def onSendCommand(
      partialFunction: PartialFunction[FSCommandPublication, Unit]
  ): Unit = {
    onCommandCallbacks = onCommandCallbacks :+ partialFunction
  }

  def onReceiveMsg(
      partialFunction: PartialFunction[
        (List[FSMessage], String, List[FSMessage]),
        Unit
      ]
  ): Unit = {
    onFsMsgCallbacks = onFsMsgCallbacks :+ partialFunction
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

  /**
    * Send auth command to freeswitch
    *
    * @param password : String
    * @return Future[CommandResponse]
    */
  private[esl] def authenticate(password: String): Future[QueueOfferResult] =
    publishNonMappingCommand(AuthCommand(password))

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
            val (messages, buffer) =
              parser.parse(unParsedBuffer + data.utf8String)
            unParsedBuffer = buffer
            /*onFsMsgCallbacks.foreach(
              _.lift(
                (messages, "ALL MSGS FROM FS : WILL FIND DUPLICATE BELOW", Nil)
              )
            )*/
            List(FSData(self, messages))
          } catch {
            case e: Exception => {
              adapter.error(logMarker, e.getMessage, e)
              onFsMsgCallbacks.foreach(_.lift((Nil, s"""ERROR PARSING fs events
                  |---------------------
                  |${unParsedBuffer + data.utf8String}
                  |---------------------""".stripMargin, Nil)))
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
      fun: (String, Future[FSSocket[FS]], Future[FS]) => Sink[FSData, Mat],
      timeout: FiniteDuration,
      needToLinger: Boolean,
      isInbound: Boolean = false
  ) = {
    lazy val timeoutFuture =
      after(duration = timeout, using = system.scheduler) {
        Future.failed(
          new TimeoutException(
            s"Socket doesn't receive any response within $timeout."
          )
        )
      }

    val fsPromise = Promise[FS]

    lazy val socketOrTimeout = Future
      .firstCompletedOf(
        Seq(fsConnectionPromise.future, fsPromise.future, timeoutFuture)
      )
      .onFailure({
        case ex =>
          adapter.error(
            logMarker,
            ex,
            s"CALL ${callId} About to shutdown due to exception in Future sink"
          )
          kill
      })

    lazy val sink = fun(callId, fsConnectionPromise.future, fsPromise.future)
      .mapMaterializedValue(Future.successful)

    /*    lazy val connectToFS = (fsData: FSData, hasConnected: Boolean) => {
      fsData.fsMessages match {
        case ::(command: CommandReply, remaining)
            if command.headers.contains(
              HeaderNames.uniqueId
            ) || (isInbound && command.replyText.fold(false)(
              _ == "+OK accepted"
            )) =>
          if (command.success) {
            if (!isInbound) {
              val fsSocket =
                FSSocket(fsConnection, ChannelData(command.headers))
              fsSocket.fsConnection.setConnectionId(
                fsSocket.channelData.headers(HeaderNames.uniqueId)
              )
              fsConnectionPromise.complete(
                Success(fsSocket)
              )
            }
            if (needToLinger) publishNonMappingCommand(LingerCommand)
            (
              fsData.copy(fsMessages = remaining),
              true,
              command :: Nil
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
            (fsData, hasConnected, Nil)
          }
        case _ => (fsData, hasConnected, Nil)
      }
    }

    lazy val doLinger = (fsData: FSData, isLingering: Boolean) => {
      fsData.fsMessages match {
        case ::(command: CommandReply, remaining) =>
          adapter.info(
            logMarker,
            s"CALL ${callId} Reply of linger command, ${isLingering}, ${command}, promise status: ${fsConnectionPromise.isCompleted}"
          )
          if (command.success) {
            (
              fsData
                .copy(fsMessages = remaining),
              true,
              command :: Nil
            )
          } else {
            (fsData, isLingering, Nil)
          }
        case _ => (fsData, isLingering, Nil)
      }
    }*/

    def processLingered(command: CommandReply) = {
      adapter.info(
        logMarker,
        s"CALL ${callId} Reply of linger command, ${command}, promise status: ${fsConnectionPromise.isCompleted}"
      )
      command.success
    }

    def performConnection(
        command: CommandReply,
        isConnectingForFirstTime: Boolean
    ) = {
      if (isConnectingForFirstTime) {
        if (command.success) {
          fsConnection.setConnectionId(command.headers(HeaderNames.uniqueId))
          fsPromise.complete(Success(fsConnection))
          fsConnectionPromise.complete(
            Success(FSSocket(fsConnection, ChannelData(command.headers)))
          )
          if (needToLinger) publishNonMappingCommand(LingerCommand)
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
        }
      }
      command.success
    }
    def performConnectionInbound(
        result: Try[FSSocket[FS]],
        isConnectingForFirstTime: Boolean
    ) = {
      if (isConnectingForFirstTime) {
        fsConnectionPromise.complete(
          result
        )
        if (needToLinger) publishNonMappingCommand(LingerCommand)
      }
      true
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

    Flow[FSData]
      .statefulMapConcat(() => {
        var hasConnected: Boolean = false
        var wasOnceConnected: Boolean = false
        var isLingering: Boolean = false
        var isFiltering: Boolean = false
        val buffer: mutable.ListBuffer[FSData] =
          mutable.ListBuffer.empty[FSData]

        var hMap = mutable.HashMap.empty[String, String]

        var authenticated = !isInbound

        def filterFSMessages(fSData: FSData): (FSData, List[FSMessage]) = {
          val (messagesWithSameId, messagesWithDifferentId) = {
            fSData.fsMessages.partition { message =>
              message.headers
                .get(HeaderNames.uniqueId)
                .fold(true)(uniqueIdHeaderValue =>
                  uniqueIdHeaderValue == getConnectionId || getOriginatedCallIds
                    .contains(uniqueIdHeaderValue)
                )
            }
          }
          if (messagesWithDifferentId.nonEmpty) {
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
          }
          fSData
            .copy(fsMessages = messagesWithSameId) -> messagesWithDifferentId
        }

        _fSData => {
          val fSData = _fSData.copy(fsMessages = _fSData.fsMessages.reverse)
          val updatedFSData = {

            lazy val (authRequest, allMsgsOtherThanAuthRequest) =
              fSData.fsMessages
                .partition(
                  _.contentType == esl.domain.ContentTypes.authRequest
                )

            if (authRequest.nonEmpty) {
              authenticated = false
              buffer.append(
                fSData.copy(fsMessages = allMsgsOtherThanAuthRequest)
              )
              onFsMsgCallbacks.foreach(
                _.lift(
                  fSData.fsMessages,
                  s"AUTH REQUEST MSG BUFFERED : wasOnceConnected=$hasConnected ; will mark as not connected now ; hasConnected=false",
                  authRequest
                )
              )
              fsConnection.authenticate("ClueCon")
              hasConnected = false
              fSData.copy(fsMessages = Nil)
            } else if (!authenticated) {
              lazy val (authenticatedMsg, allMsgsOtherThanAuth) = {
                if (isInbound || wasOnceConnected) {
                  fSData.fsMessages.partition({
                    case cmd: CommandReply =>
                      cmd.replyText.fold(false)(_ == "+OK accepted")
                  })
                } else {
                  Nil -> fSData.fsMessages
                }
              }

              (authenticatedMsg.nonEmpty) match {
                case true if wasOnceConnected || isInbound => {
                  authenticated = true
                  hasConnected = wasOnceConnected
                  val allMessages = fSData.copy(fsMessages =
                    buffer
                      .flatMap(_.fsMessages)
                      .toList ++ allMsgsOtherThanAuth
                  )
                  buffer.clear()
                  val (filteredFs, removedFsMsgs) =
                    filterFSMessages(allMessages)
                  onFsMsgCallbacks.foreach(
                    _.lift(
                      fSData.fsMessages,
                      s"NOW CONNECTED on Authentication : wasOnceConnected:$wasOnceConnected hasConnected=$hasConnected ;isAuthSuccessMsg:true isConnect=false ; ${removedFsMsgs.length + authenticatedMsg.length}/${allMessages.fsMessages.length} messages dropped",
                      removedFsMsgs ++ authenticatedMsg
                    )
                  )

                  if (isInbound && !wasOnceConnected) {
                    fsPromise.complete(Success(fsConnection))
                  }
                  filteredFs
                }
                case (nowAuthenticated) => {
                  authenticated = nowAuthenticated
                  buffer.append(
                    fSData.copy(fsMessages = allMsgsOtherThanAuth)
                  )
                  onFsMsgCallbacks.foreach(
                    _.lift(
                      fSData.fsMessages,
                      s"ATTEMPT CONNECTION : wasOnceConnected:$wasOnceConnected hasConnected=$hasConnected ; isAuthSuccessMsg:true; isConnect=false; ${authenticatedMsg.length}/${fSData.fsMessages.length} messages dropped",
                      authenticatedMsg
                    )
                  )
                  fSData.copy(fsMessages = Nil)
                }
              }

            } else if (!hasConnected) {

              def isAnswered(eventMessage: EventMessage) = {
                eventMessage.eventName.fold(false)(
                  _.name == "CHANNEL_STATE"
                ) && eventMessage.headers
                  .get("Channel-Call-State")
                  .contains("ACTIVE")
              }

              def isConnectionError(eventMessage: EventMessage) = {
                val earlyStates = Set("DOWN", "DIALING", "RINGING", "EARLY")
                val endStates = Set("HANGUP")
                eventMessage.eventName.fold(false)(
                  _.name == "CHANNEL_CALLSTATE"
                ) && eventMessage.headers
                  .get("Original-Channel-Call-State")
                  .exists(earlyStates.contains) && eventMessage.headers
                  .get("Channel-Call-State")
                  .exists(endStates.contains)
              }

              val (
                connection,
                connectionMsgs,
                allMsgsOtherThanConnection
              ) = {
                if (isInbound) {
                  val isConnectOrDisconnect = fSData.fsMessages
                    .collect({
                      case event: EventMessage
                          if event.channelCallUUID.fold(false)(_ == callId) =>
                        event.headers.foreach({
                          case (key, value) =>
                            hMap.getOrElseUpdate(key, value)
                        })

                        if (isAnswered(event)) {
                          Some(true)
                        } else if (isConnectionError(event)) {
                          Some(false)
                        } else Option.empty[Boolean]
                    })
                    .collectFirst({
                      case Some(value) => value
                    })

                  isConnectOrDisconnect match {
                    case Some(true) => {
                      hasConnected = performConnectionInbound(
                        Success(
                          FSSocket(fsConnection, ChannelData(hMap.toMap))
                        ),
                        !wasOnceConnected
                      )
                      logger.info(
                        """CALL ID OUTBOUND {} CONNECTED
                          |Agg Headers
                          |{}""".stripMargin,
                        callId,
                        hMap.mkString("\n")
                      )
                    }
                    case Some(false) => {
                      hasConnected = performConnectionInbound(
                        Failure(
                          FSSocketError(fsConnection, ChannelData(hMap.toMap))(
                            "TODO"
                          )
                        ),
                        !wasOnceConnected
                      )
                      logger.info(
                        """CALL ID OUTBOUND {} CONNECTION ERROR
                          |Agg Headers
                          |{}""".stripMargin,
                        callId,
                        hMap.mkString("\n")
                      )
                    }
                    case _ => {
                      logger.debug(
                        """CALL ID OUTBOUND {} WAITING FOR CONNECTION
                          |Agg Headers
                          |{}""".stripMargin,
                        callId,
                        hMap.mkString("\n")
                      )
                    }
                  }
                  (isConnectOrDisconnect, Nil, fSData.fsMessages)
                } else {
                  val (connection, allMsgsOtherThanConnection) =
                    fSData.fsMessages.partition({
                      case cmd: CommandReply =>
                        cmd.headers
                          .contains(HeaderNames.uniqueId) && cmd.eventName
                          .fold(false)(_ == EventNames.ChannelData.name)
                    })
                  if (connection.nonEmpty) {
                    hasConnected = performConnection(
                      connection.head.asInstanceOf[CommandReply],
                      !wasOnceConnected
                    )
                  }
                  (
                    if (connection.nonEmpty) Some(true)
                    else Option.empty[Boolean],
                    connection,
                    allMsgsOtherThanConnection
                  )
                }
              }

              if (isInbound) {
                val newFsData = if (wasOnceConnected) {
                  val (filteredFs, removedFsMsgs) = filterFSMessages(fSData)
                  onFsMsgCallbacks.foreach(
                    _.lift(
                      fSData.fsMessages,
                      s"NOW CONNECTED : hasConnected=$hasConnected ;isAuthenticated:$authenticated isConnect=true ; ${removedFsMsgs.length}/${fSData.fsMessages.length} messages dropped",
                      removedFsMsgs
                    )
                  )
                  filteredFs
                } else {
                  onFsMsgCallbacks.foreach(
                    _.lift(
                      fSData.fsMessages,
                      s"ATTEMPT CONNECTION : hasConnected=$hasConnected ; isAuthenticated:$authenticated ; isConnect=true; 0/${fSData.fsMessages.length} messages dropped",
                      Nil
                    )
                  )
                  fSData
                }
                if (connection.isDefined && hasConnected) {
                  wasOnceConnected = true
                }
                newFsData
              } else {
                if (connection.getOrElse(false)) {
                  wasOnceConnected = true
                  if (hasConnected) {
                    val allMessages = fSData.copy(fsMessages =
                      buffer
                        .flatMap(_.fsMessages)
                        .toList ++ allMsgsOtherThanConnection
                    )
                    buffer.clear()
                    val (filteredFs, removedFsMsgs) =
                      filterFSMessages(allMessages)
                    onFsMsgCallbacks.foreach(
                      _.lift(
                        fSData.fsMessages,
                        s"NOW CONNECTED : hasConnected=$hasConnected ;isAuthenticated:$authenticated isConnect=true ; ${removedFsMsgs.length + connectionMsgs.length}/${allMessages.fsMessages.length} messages dropped",
                        removedFsMsgs ++ connectionMsgs
                      )
                    )
                    filteredFs
                  } else {
                    buffer.append(
                      fSData.copy(fsMessages = allMsgsOtherThanConnection)
                    )
                    onFsMsgCallbacks.foreach(
                      _.lift(
                        fSData.fsMessages,
                        s"ATTEMPT CONNECTION : hasConnected=$hasConnected ; isAuthenticated:$authenticated ; isConnect=true; ${connectionMsgs.length}/${fSData.fsMessages.length} messages dropped",
                        connectionMsgs
                      )
                    )
                    fSData.copy(fsMessages = Nil)
                  }
                } else {
                  buffer.append(fSData)
                  onFsMsgCallbacks.foreach(
                    _.lift(
                      fSData.fsMessages,
                      s"ALL MSG BUFFERED : hasConnected=$hasConnected; isAuthenticated:$authenticated ; isConnect=false",
                      Nil
                    )
                  )
                  fSData.copy(fsMessages = Nil)
                }
              }

            } else {

              if (needToLinger && !isLingering) {
                val (lingerReply, allOtherMsgsOtherThanLingered) =
                  fSData.fsMessages.partition({
                    case cmd: CommandReply =>
                      cmd.replyText.fold(false)(_ == "+OK will linger")
                  })

                isLingering =
                  processLingered(lingerReply.head.asInstanceOf[CommandReply])
                val (filteredFs, removedFsMsgs) = filterFSMessages(
                  fSData.copy(fsMessages = allOtherMsgsOtherThanLingered)
                )
                val allDroppedMessages = lingerReply ::: removedFsMsgs
                onFsMsgCallbacks.foreach(
                  _.lift(
                    fSData.fsMessages,
                    s"LINGERED : hasConnected=$hasConnected ; isAuthenticated:$authenticated; isConnect=false; ${allDroppedMessages.length}/${fSData.fsMessages.length} messages dropped",
                    allDroppedMessages
                  )
                )
                filteredFs
              } else {
                val (filteredFs, removedFsMsgs) = filterFSMessages(fSData)
                onFsMsgCallbacks.foreach(
                  _.lift(
                    fSData.fsMessages,
                    s"NORMAL PROCESSING : hasConnected=$hasConnected; isAuthenticated:$authenticated ; isConnect=false; ${removedFsMsgs.length}/${fSData.fsMessages.length} messages dropped",
                    removedFsMsgs
                  )
                )
                filteredFs
              }
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
      .toMat(sink)(Keep.right)

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

  private val space = "    "

  /**
    * This will get an element from `eventMap` map by event's uuid
    * Complete promises after receiving `CHANNEL_EXECUTE` and `CHANNEL_EXECUTE_COMPLETE` events
    *
    * @param eventMessage : EventMessage
    * @return EventMessage
    */
  private def handleFSEventMessage(eventMessage: EventMessage): EventMessage = {

    lazy val conferenceAppSet = Set(
      "add-member",
      "del-member",
      "mute-member",
      "deaf-member",
      "unmute-member",
      "undeaf-member",
      "hold-member",
      "unhold-member",
      "kick-member"
    )

    def completeAndRemoveFromMap(
                                  command: FSCommand,
                                  executeComplete: Promise[EventMessage],
                                  canRemove: Boolean
                                ): Unit = {
      executeComplete.complete(Success(eventMessage))
      if (canRemove) {
        eventMap.remove(command.eventUuid)
        adapter.info(
          logMarker,
          s"""handleFSEventMessage for app id ${command.eventUuid} Removing entry
             |>> TYPE
             |EVENT ${eventMessage.eventName.getOrElse("NA")}
             |>> HEADERS
             |${
            eventMessage.headers
              .map(h => h._1 + " : " + h._2)
              .mkString(space, "\n" + space, "")
          }
             |>> BODY
             |${eventMessage.body}
             |>> MAP is below
             |${
            eventMap
              .map({
                case (key, value) =>
                  s"""appId: $key
                     |command
                     |$value""".stripMargin
              })
              .mkString("\n")
          }""".stripMargin
        )
      }
    }

    (
      eventMessage.applicationUuid,
      eventMessage.applicationUuid.flatMap(eventMap.get),
      eventMessage.uuid,
      eventMessage.eventName,
      eventMessage.jobUuid,
      eventMessage.jobUuid.flatMap(eventMap.get)
    ) match {
      case (_, _, _, Some(EventNames.Custom), _, _)
        if eventMessage.conferenceName.isDefined &&
          (eventMessage.action.isDefined && conferenceAppSet.contains(
            eventMessage.action.get
          )) =>
        adapter.info(
          logMarker,
          s"""Channel custom event ${eventMessage.action} for callId
             |${eventMessage.headers.get("Unique-ID")}
             |>> MAP command is below
             |${
            eventMap
              .map({ item =>
                s"""appId: ${item._1}
                   |command
                   |${item._2.command}
                   |command type ${item._2.command.getClass}""".stripMargin
              })
              .mkString("\n")
          }""".stripMargin
        )
        val findResult =
          eventMap.find { //TODO change eventMap key for this command
            case (_, CommandToQueue(command: AddToConference, _, _))
              if eventMessage.conferenceName.contains(
                command.conferenceId
              ) && eventMessage.action.contains("add-member") =>
              true
            case (_, CommandToQueue(command: ConferenceCommand, _, _))
              if eventMessage.conferenceName.contains(
                command.conferenceId
              ) && (command.command match {
                case SendConferenceCommand(_, cmd, _)
                  if eventMessage.action.fold(false)(_.startsWith(cmd)) =>
                  true
                case _ => false
              }) =>
              true
            case _ => false
          }
        findResult match {
          case Some((key, command)) =>
            if (!command.executeComplete.isCompleted)
              command.executeComplete.complete(Success(eventMessage))
            if (command.executeEvent.isCompleted) eventMap.remove(key)
          case _ =>
        }

      case (_, _, uuid, Some(EventNames.ChannelUnhold), _, _) =>
        for {
          channelId <- uuid
          command <-
            eventMap.collectFirst { //TODO change eventMap key for this command
              case (_, queCommand@CommandToQueue(command: OffHold, _, _))
                if command.config.channelUuid == channelId =>
                queCommand
            }
        } yield {
          command.executeComplete.complete(Success(eventMessage))
          if (command.executeEvent.isCompleted) {
            adapter.info(
              logMarker,
              s"""Channel call state event for callId
                 |${eventMessage.headers("Caller-Unique-ID")}
                 |>> MAP command is below
                 |${
                eventMap
                  .map({ item =>
                    s"""appId: ${item._1}
                       |command
                       |${item._2.command}
                       |command type ${item._2.command.getClass}""".stripMargin
                  })
                  .mkString("\n")
              }""".stripMargin
            )
          }
        }
      /*
      case (
            Some(appId),
            Some(
              CommandToQueue(command: ListenIn, executeEvent, executeComplete)
            ),
            _,
            Some(EventNames.ChannelExecute)
          ) if appId == command.eventUuid => {
        executeEvent.complete(Success(eventMessage))
        if (executeComplete.isCompleted) eventMap.remove(appId)
      }
      case (
            _,
            _,
            _,
            Some(EventNames.ChannelPark)
          ) => {

        eventMap.collectFirst({
          case (_, CommandToQueue(command: Dial, executeEvent, executeComplete))
              if eventMessage.uuid
                .fold(false)(_ == command.config.channelUuid) => {
            executeComplete.complete(Success(eventMessage))
            if (executeEvent.isCompleted) {
              eventMap.remove(command.eventUuid)
            }
          }
          case (
                _,
                CommandToQueue(
                  command: DialSession,
                  executeEvent,
                  executeComplete
                )
              )
              if eventMessage.uuid
                .fold(false)(_ == command.options.uniqueId) => {
            executeComplete.complete(Success(eventMessage))
            if (executeEvent.isCompleted) {
              eventMap.remove(command.eventUuid)
            }
          }
        })
      }
       */
      case (
        _,
        Some(CommandToQueue(command: Dial, _, _)),
        _,
        Some(EventNames.ChannelExecute),
        _,
        _
        ) =>
        adapter.info(
          logMarker,
          s"""command skipped $command
             |event ${eventMessage.eventName}
             |Channel call state event for callId
             |${eventMessage.headers("Caller-Unique-ID")}
             |>> MAP command is below
             |${
            eventMap
              .map({ item =>
                s"""appId: ${item._1}
                   |command
                   |${item._2.command}
                   |command type ${item._2.command.getClass}""".stripMargin
              })
              .mkString("\n")
          }""".stripMargin
        )
      //skip for dial command
      case (
        _,
        Some(CommandToQueue(command: DialSession, _, _)),
        _,
        Some(EventNames.ChannelExecute),
        _,
        _
        ) =>
      //skip for dial commands
      case (
        _,
        Some(CommandToQueue(command: Dial, _, _)),
        _,
        Some(EventNames.ChannelExecuteComplete),
        _,
        _
        ) =>
        adapter.info(
          logMarker,
          s"""command skipped $command
             |event ${eventMessage.eventName}
             |Channel call state event for callId
             |${eventMessage.headers("Caller-Unique-ID")}
             |>> MAP command is below
             |${
            eventMap
              .map({ item =>
                s"""appId: ${item._1}
                   |command
                   |${item._2.command}
                   |command type ${item._2.command.getClass}""".stripMargin
              })
              .mkString("\n")
          }""".stripMargin
        )
      //skip for dial command
      case (
        _,
        Some(CommandToQueue(command: DialSession, _, _)),
        _,
        Some(EventNames.ChannelExecuteComplete),
        _,
        _
        ) =>
      //skip for dial commands

      case (_, Some(commandToQueue), _, Some(EventNames.ChannelExecute), _, _)
        if !eventMessage.answerState.contains(AnswerStates.Early) &&
          !eventMessage.applicationName.contains("set") =>
        if (!commandToQueue.executeEvent.isCompleted)
          commandToQueue.executeEvent.complete(Success(eventMessage))
        if (commandToQueue.executeComplete.isCompleted)
          eventMap.remove(commandToQueue.command.eventUuid)
      case (
        Some(appId),
        Some(commandToQueue),
        _,
        Some(EventNames.ChannelExecuteComplete),
        _,
        _
        )
        if !eventMessage.answerState.contains(AnswerStates.Early) &&
          !eventMessage.applicationName.contains("set") =>
        if (!commandToQueue.executeComplete.isCompleted)
          commandToQueue.executeComplete.complete(Success(eventMessage))
        if (commandToQueue.executeEvent.isCompleted)
          eventMap.remove(commandToQueue.command.eventUuid)
        adapter.info(
          logMarker,
          s"""handleFSEventMessage for app id $appId Removing entry
             |>> TYPE
             |EVENT ${eventMessage.eventName.getOrElse("NA")}
             |>> HEADERS
             |${
            eventMessage.headers
              .map(h => h._1 + " : " + h._2)
              .mkString(space, "\n" + space, "")
          }
             |>> BODY
             |${eventMessage.body}
             |>> MAP is below
             |${
            eventMap
              .map({ item =>
                s"""appId: ${item._1}
                   |command
                   |${item._2.command}""".stripMargin
              })
              .mkString("\n")
          }""".stripMargin
        )
      case (
        Some(appId),
        Some(CommandToQueue(command: Record, execute, executeComplete)),
        _,
        Some(EventNames.ChannelExecuteComplete),
        _,
        _
        ) if appId == command.eventUuid =>
        completeAndRemoveFromMap(command, executeComplete, execute.isCompleted)
      case (
        Some(appId),
        Some(CommandToQueue(command: PlayFile, execute, executeComplete)),
        _,
        Some(EventNames.ChannelExecuteComplete),
        _,
        _
        ) if appId == command.eventUuid =>
        completeAndRemoveFromMap(command, executeComplete, execute.isCompleted)
      case (_, _, _, Some(EventNames.Api), _, _) =>
        if (
          eventMessage.apiCommand.contains(
            "create_uuid"
          ) && eventMessage.headers.contains("API-Command-Argument")
        ) {

          val queuedCommand =
            eventMap.get(eventMessage.headers("API-Command-Argument"))
          queuedCommand.map {
            case CommandToQueue(_: CreateUUID, executeEvent, _) =>
              executeEvent.complete(Success(eventMessage))
          }
        }

      case (_, _, _, Some(EventNames.Api), _, _) =>
        if (
          eventMessage.apiCommand.contains(
            "uuid_bridge"
          ) && eventMessage.headers.contains("API-Command-Argument")
        ) {

          val targets = eventMessage.headers
            .getOrElse("API-Command-Argument", "")
            .split("%20")

          val queuedCommand =
            eventMap.values.find {
              case CommandToQueue(command: BridgeUuid, _, _) =>
                command.targets
                  .zip(targets)
                  .forall(tuple => tuple._1 == tuple._2)
            }
          queuedCommand.map {
            case CommandToQueue(
            command: BridgeUuid,
            executeEvent,
            executeComplete
            ) =>
              completeAndRemoveFromMap(
                command,
                executeEvent,
                executeComplete.isCompleted
              )
          }
        }
      case (
        _,
        _,
        _,
        Some(EventNames.BackgroundJob),
        Some(jobId),
        Some(commandToQueue: CommandToQueue)
        ) if commandToQueue.command.isInstanceOf[Dial]  =>
      case (
        _,
        _,
        _,
        Some(EventNames.BackgroundJob),
        Some(jobId),
        Some(commandToQueue: CommandToQueue)
        ) if commandToQueue.command.isInstanceOf[DialSession]  =>

      case (
        _,
        _,
        _,
        Some(EventNames.BackgroundJob),
        Some(jobId),
        Some(commandToQueue: CommandToQueue)
        ) =>
        //val jobId = eventMap.get(eventMessage.jobUuid.getOrElse(""))

        /*>> TYPE
            EVENT BACKGROUND_JOB
        >> HEADERS
            Event-Date-GMT : Fri,%2018%20Aug%202023%2009%3A33%3A41%20GMT
            Job-Owner-UUID : 2896817a-29f3-4d73-a7b5-1c177f921fb3
            Event-Date-Local : 2023-08-18%2009%3A33%3A41
            Job-Command : uuid_hold
            Job-UUID : ad5f32b1-2f46-4886-9bac-2a621315dcba
            FreeSWITCH-IPv6 : %3A%3A1
            Event-Date-Timestamp : 1692351221822295
            Event-Calling-Function : api_exec
            Core-UUID : bc864664-8510-4bab-99e8-57499dc7575e
            Event-Calling-Line-Number : 1572
            Event-Calling-File : mod_event_socket.c
            Content-Length : 12
            Event-Sequence : 3833
            Event-Name : BACKGROUND_JOB
            Content-Type : text/event-plain
            Job-Command-Arg : off%202896817a-29f3-4d73-a7b5-1c177f921fb3
            FreeSWITCH-IPv4 : 192.168.0.109
            FreeSWITCH-Switchname : sysgears-Latitude-3510
            FreeSWITCH-Hostname : sysgears-Latitude-3510
        >> BODY
            +OK Success
         */

        if ((eventMessage.jobCommand.fold(false)(
          _ == "uuid_hold"
        ) && eventMessage.jobCommandArg.fold(false)(
          _.startsWith("off")
        )) || eventMessage.jobCommand.fold(false)(_ == "conference")) {

          commandToQueue.executeEvent.complete(Success(eventMessage))
          if (commandToQueue.executeComplete.isCompleted) {
            eventMap.remove(commandToQueue.command.eventUuid)
            adapter.info(
              logMarker,
              s"""handleFSEventMessage for background job id $jobId Removing entry
                 |>> TYPE
                 |EVENT ${eventMessage.eventName.getOrElse("NA")}
                 |>> HEADERS
                 |${
                eventMessage.headers
                  .map(h => h._1 + " : " + h._2)
                  .mkString(space, "\n" + space, "")
              }
                 |>> BODY
                 |${eventMessage.body}
                 |>> MAP is below
                 |${
                eventMap
                  .map({ item =>
                    s"""appId: ${item._1}
                       |command
                       |${item._2.command}""".stripMargin
                  })
                  .mkString("\n")
              }""".stripMargin
            )
          }
        } else {
          commandToQueue.executeComplete.complete(Success(eventMessage))
          if (commandToQueue.executeEvent.isCompleted)
            eventMap.remove(commandToQueue.command.eventUuid)
          adapter.info(
            logMarker,
            s"""handleFSEventMessage for background job id $jobId Removing entry
               |>> TYPE
               |EVENT ${eventMessage.eventName.getOrElse("NA")}
               |>> HEADERS
               |${
              eventMessage.headers
                .map(h => h._1 + " : " + h._2)
                .mkString(space, "\n" + space, "")
            }
               |>> BODY
               |${eventMessage.body}
               |>> MAP is below
               |${
              eventMap
                .map({ item =>
                  s"""appId: ${item._1}
                     |command
                     |${item._2.command}""".stripMargin
                })
                .mkString("\n")
            }""".stripMargin
          )
        }

      case (_, _, _, Some(EventNames.ChannelState), _, _) =>
        adapter.info(
          logMarker,
          s"""Channel state event for callId
             |${eventMessage.headers("Caller-Unique-ID")}
             |>> MAP command is below
             |${
            eventMap
              .map({ item =>
                s"""appId: ${item._1}
                   |command
                   |${item._2.command}
                   |command type ${item._2.command.getClass}""".stripMargin
              })
              .mkString("\n")
          }""".stripMargin
        )
        val findResult =
          eventMap.find { //TODO change eventMap key for this command
            case (
              _,
              CommandToQueue(command: Dial, _, _)
              ) =>
              eventMessage.callerUniqueId.contains(command.options.uniqueId)
            case (_, CommandToQueue(command: DialSession, _, _)) =>
              eventMessage.callerUniqueId.contains(command.options.uniqueId)
            case _ => false
          }
        adapter.info(s"Command from map $findResult")
        findResult match {
          case Some((key, command)) =>
            if (!command.executeEvent.isCompleted)
              command.executeEvent.complete(Success(eventMessage))
            if (command.executeComplete.isCompleted) eventMap.remove(key)
          case _ =>
        }
      case (_, _, _, Some(EventNames.ChannelCallState), _, _) =>
        adapter.info(
          logMarker,
          s"""Channel call state event for callId
             |${eventMessage.headers("Caller-Unique-ID")}
             |>> MAP command is below
             |${
            eventMap
              .map({ item =>
                s"""appId: ${item._1}
                   |command
                   |${item._2.command}
                   |command type ${item._2.command.getClass}""".stripMargin
              })
              .mkString("\n")
          }""".stripMargin
        )
        (for {
          (key, command) <- eventMap.find {
            case (_, CommandToQueue(command: Dial, _, _)) =>
              eventMessage.callerUniqueId.contains(command.options.uniqueId)
            case (_, CommandToQueue(command: DialSession, _, _)) =>
              eventMessage.callerUniqueId.contains(command.options.uniqueId)
            case _ => false
          }.toList
          promise <- eventMessage.headers.get("Channel-Call-State") match {
            case Some("HANGUP" | "ACTIVE") =>
              command.executeEvent :: command.executeComplete :: Nil
            case _ => command.executeEvent :: Nil
          }
        } yield {
          if (!promise.isCompleted) {
            promise.complete(Success(eventMessage))
          }
          (key, command)
        }).headOption.foreach({
          case (key, command) => {
            if (
              command.executeComplete.isCompleted && command.executeComplete.isCompleted
            ) eventMap.remove(key)
          }
        })

      case (_, _, _, Some(EventNames.MediaBugStart), _, _) => {
        eventMap
          .collectFirst({
            case (
              key,
              CommandToQueue(
              command: ListenIn,
              executeEvent,
              executeComplete
              )
              )
              if eventMessage.eavesdropTarget
                .fold(false)(_ == command.listenCallId) =>
              executeComplete.complete(Success(eventMessage))
              if (executeEvent.isCompleted) Some(key)
              else Option.empty[String]
          })
          .flatten
          .foreach(eventMap.remove)
      }

      case (appId, _, _, _, jobId, _) =>
        adapter.warning(
          logMarker,
          s"""handleFSEventMessage for app id $appId jobId $jobId Unable to handle Command (unexpected eventName header)
             |>> TYPE
             |EVENT ${eventMessage.eventName.fold("NA")(_.name)}
             |>> HEADERS
             |${
            eventMessage.headers
              .map(h => h._1 + " : " + h._2)
              .mkString(space, "\n" + space, "")
          }
             |>> BODY
             |${eventMessage.body}""".stripMargin
        )
      case _ =>
        adapter.debug(
          logMarker,
          s"""handleFSEventMessage Unable to find application id header for event message
             |>> TYPE
             |EVENT ${eventMessage.eventName}
             |>> HEADERS
             |${
            eventMessage.headers
              .map(h => h._1 + " : " + h._2)
              .mkString(space, "\n" + space, "")
          }
             |>> BODY
             |${eventMessage.body}""".stripMargin
        )
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

    val cmdPromise = Promise[QueueOfferResult]

    onCommandCallbacks
      .foreach(
        _.lift(
          FireAndForgetFSCommand(command, cmdPromise.future)
        )
      )

    val offerF = queue
      .offer(command)

    cmdPromise.completeWith(offerF)
    offerF

  }

  /**
    * publish FS command to FS, An enqueue command into `commandQueue` and add command with events promises into `eventMap`
    *
    * @param command : FSCommand FS command
    * @return Future[CommandResponse]
    */
  private def publishCommand(command: FSCommand): Future[CommandResponse] = {

    val cmdPromise = Promise[CommandResponse]

    onCommandCallbacks.foreach(
      _.lift(AwaitingFSCommand(command, cmdPromise.future))
    )

    val offerF = queue
      .offer(command)
      .flatMap({
        case QueueOfferResult.Enqueued => {
          val (commandReply, commandToQueue, cmdResponse) =
            buildCommandAndResponse(command)
          commandQueue.enqueue(commandReply)
          val appId = command.eventUuid
          eventMap.put(appId, commandToQueue)
          adapter.info(
            logMarker,
            s"""publishCommand for app id $appId Enqueued and Added to lookup
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
          Future.successful(cmdResponse)
        }
        case QueueOfferResult.Failure(cause) => {
          Future.failed(
            new Exception(s"""Command ${command.eventUuid} failed on offer
                 |$command""".stripMargin, cause)
          )
        }
        case offer => {
          Future.failed(
            new Exception(s"""Command ${command.eventUuid} $offer on offer
                 |$command""".stripMargin)
          )
        }
      })

    cmdPromise.completeWith(offerF)

    offerF

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

  def listen(
      bargeIn: Boolean,
      options: DialConfig,
      listenCallId: String,
      config: ApplicationCommandConfig = ApplicationCommandConfig()
  ): Future[CommandResponse] = {
    publishCommand(
      ListenIn(config, bargeIn, options, listenCallId)
    )
  }

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
      config: ApplicationCommandConfig = ApplicationCommandConfig(),
      app: Option[String] = Option.empty
  ): Future[CommandResponse] =
    publishCommand(Break(config, app))

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
      uuid: String
  ): Future[CommandResponse] = {
    setOriginatedCallIds(uuid)
    publishCommand(FilterUUId(uuid))
  }
  def createUUID(
      config: ApplicationCommandConfig = ApplicationCommandConfig()
  ): Future[CommandResponse] = {
    publishCommand(CreateUUID(config))
  }

  def filterX(
      filter: String,
      config: ApplicationCommandConfig = ApplicationCommandConfig()
  ): Future[CommandResponse] = {
    publishCommand(FilterX(filter, config))
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
    * Allows one channel to bridge itself to the a or b leg of another call. The allMsgsOtherthanAuthRequest leg of the original call gets hungup
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
    subscribeEvents(events.toList)

  def subscribeEvents(events: List[EventName]): Future[CommandResponse] =
    publishCommand(SubscribeEvents(events))

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
      queueOfferResult: Future[QueueOfferResult]
  ) extends FSCommandPublication

  case class AwaitingFSCommand(
      command: FSCommand,
      result: Future[CommandResponse]
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

  case class FSSocketError[+FS <: FSConnection](
      fsConnection: FS,
      channelData: ChannelData
  )(cause: String)
      extends Exception(s"FSSocket failed $cause")

  case class FSData(fSConnection: FSConnection, fsMessages: List[FSMessage])

  case class ChannelData(headers: Map[String, String]) {
    lazy val uuid: Option[String] = headers.get(HeaderNames.uniqueId)
  }

}
