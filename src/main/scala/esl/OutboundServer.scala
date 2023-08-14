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
import akka.event.{LogMarker, MarkerLoggingAdapter}
import akka.stream.{
  ActorAttributes,
  Attributes,
  KillSwitches,
  Materializer,
  NeverMaterializedException,
  OverflowStrategy,
  Supervision
}
import akka.stream.scaladsl.Tcp.IncomingConnection
import akka.stream.scaladsl.{BidiFlow, Framing, Keep, Sink, Source, Tcp}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import esl.FSConnection.{FSCommandPublication, FSData, FSSocket}
import esl.domain.FSMessage

import java.util.UUID
import scala.language.postfixOps
import scala.concurrent.duration.{
  Duration,
  DurationInt,
  FiniteDuration,
  SECONDS
}
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object OutboundServer {

  /**
    * type alias for Flow Completion callbacks
    * arg 1 = IncomingConnection
    * arg 2 = Upstream Completion Future
    * arg 3 = Downstream Completion Future
    */
  type OnConnectionCallBack[-Mat] =
    (
        String,
        IncomingConnection,
        OutboundFSConnection,
        Future[Done],
        Future[Mat]
    ) => Unit

  object OnConnectionCallBack {
    lazy val noop: OnConnectionCallBack[Any] =
      (
          _: String,
          _: IncomingConnection,
          _: OutboundFSConnection,
          _: Future[Done],
          _: Future[Any]
      ) => Unit
  }

  private val address = "freeswitch.outbound.address"
  private val port = "freeswitch.outbound.port"
  private val fsTimeout = "freeswitch.outbound.startup.timeout"
  private val linger = "freeswitch.outbound.linger"
  private val debugLogs = "freeswitch.logs.debug"
  private val defaultTimeout = Duration(5, SECONDS)

  /**
    * Create a OutBound server with given configuration and parser
    *
    * @param config       : Config this configuration must have `freeswitch.outbound.address` and `freeswitch.outbound.address`
    * @param system       : ActorSystem
    * @param materializer : Materializer
    * @return OutboundServer
    */
  def apply(config: Config)(implicit
      system: ActorSystem,
      materializer: Materializer,
      adapter: MarkerLoggingAdapter
  ): OutboundServer =
    new OutboundServer(config)

  /**
    * This will create a OutBound server for given interface and port
    *
    * @param interface    : String name/ip address of the server
    * @param port         : Int port number of the server
    * @param system       : ActorSystem
    * @param materializer : Materializer
    * @return OutboundServer
    */
  def apply(
      interface: String,
      port: Int,
      timeout: FiniteDuration = defaultTimeout,
      linger: Boolean = true,
      enableDebugLogs: Boolean = false
  )(implicit
      system: ActorSystem,
      materializer: Materializer,
      adapter: MarkerLoggingAdapter
  ): OutboundServer =
    new OutboundServer(interface, port, timeout, linger, enableDebugLogs)

}

class OutboundServer(
    address: String,
    port: Int,
    timeout: FiniteDuration,
    linger: Boolean,
    enableDebugLogs: Boolean
)(implicit
    system: ActorSystem,
    materializer: Materializer,
    adapter: MarkerLoggingAdapter
) extends LazyLogging {
  implicit private val ec = system.dispatcher

  def this(
      config: Config
  )(implicit
      system: ActorSystem,
      materializer: Materializer,
      adapter: MarkerLoggingAdapter
  ) =
    this(
      config.getString(OutboundServer.address),
      config.getInt(OutboundServer.port),
      Duration(
        config.getDuration(OutboundServer.fsTimeout).getSeconds,
        SECONDS
      ),
      config.getBoolean(OutboundServer.linger),
      config.hasPath(OutboundServer.debugLogs) && config.getBoolean(
        OutboundServer.debugLogs
      )
    )

  /** This function will start a tcp server with given Sink. any free switch messages materialize to given sink.
    * `FsConnection`'s helper function allow you to send/push message to downstream.
    *
    * @param fun                  this function will receive Fs connection and any incoming Free switch message materialize to the given sink.
    * @param onFsConnectionStart this function will execute when Fs connection is closed.
    * @return The stream is completed successfully or not
    */
  def startWith[Mat](
      fun: (
          String,
          Future[FSSocket[OutboundFSConnection]],
          Future[OutboundFSConnection]
      ) => Sink[FSData, Mat],
      onFsConnectionStart: OutboundServer.OnConnectionCallBack[Mat] =
        OutboundServer.OnConnectionCallBack.noop,
      onSendCommand: Option[
        (
            String,
            OutboundFSConnection
        ) => PartialFunction[FSCommandPublication, Unit]
      ] = None,
      onFsMsg: Option[(String, OutboundFSConnection) => PartialFunction[
        (List[FSMessage], String, List[FSMessage]),
        Unit
      ]] = None
  ): Future[Tcp.ServerBinding] =
    server(
      fun,
      fsConnection => fsConnection.handler(),
      onFsConnectionStart,
      onSendCommand,
      onFsMsg
    )

  /** This function will start a tcp server for given sink,source and flow.
    *
    * @param fun                  this function will receive Fs connection and any incoming message materialize to the given sink
    * @param flow                 Source's element `T2` push to downstream
    *                             BidiFlow will transform incoming ByteString into `T1` type and outgoing `T2` into ByteString
    * @param onFsConnectionStart this function will execute when Fs connection is closed
    * @tparam T type of data transform into ByteString
    * @return The stream is completed successfully or not
    */
  private[this] def server[T, Mat1](
      fun: (
          String,
          Future[FSSocket[OutboundFSConnection]],
          Future[OutboundFSConnection]
      ) => Sink[FSData, Mat1],
      flow: OutboundFSConnection => (
          Future[Done],
          Source[T, NotUsed],
          BidiFlow[ByteString, FSData, T, ByteString, NotUsed]
      ),
      onFsConnectionStart: OutboundServer.OnConnectionCallBack[Mat1],
      onSendCommand: Option[
        (
            String,
            OutboundFSConnection
        ) => PartialFunction[FSCommandPublication, Unit]
      ] = None,
      onFsMsg: Option[(String, OutboundFSConnection) => PartialFunction[
        (List[FSMessage], String, List[FSMessage]),
        Unit
      ]] = None
  ): Future[Tcp.ServerBinding] = {

    val sink = Sink.foreach[IncomingConnection] { connection =>
      val fsConnection = OutboundFSConnection(enableDebugLogs)
      val callId = UUID.randomUUID().toString.replace("-", "")
      onFsMsg.foreach(fn => fsConnection.onReceiveMsg(fn(callId, fsConnection)))
      onSendCommand.foreach(fn =>
        fsConnection.onSendCommand(fn(callId, fsConnection))
      )

      fsConnection.connect().map { _ =>
        lazy val sink = fsConnection.init(
          callId,
          Promise[FSSocket[OutboundFSConnection]](),
          fsConnection,
          fun,
          timeout,
          linger
        )

        val (upStreamCompletion, source, protocol) = flow(fsConnection)

        val decider: Supervision.Decider = {
          case ne: NullPointerException => {
            adapter.error(
              fsConnection.logMarker,
              ne,
              s"CALL ${callId} NullPointerException in FS Server Flow; will Resume"
            )
            Supervision.Resume
          }
          case ex => {
            adapter.error(
              fsConnection.logMarker,
              ex,
              s"CALL ${callId} Exception in FS Server Flow; will Stop"
            )
            Supervision.Stop
          }
        }

        val (_, downStreamStarted: Future[Mat1]) = {
          val flowStage1 = connection.flow
            .join(protocol)
            .recover {
              case e => {
                adapter.error(fsConnection.logMarker, e.getMessage, e)
                throw e
              }
            }
            .buffer(1000, OverflowStrategy.fail)

          flowStage1
            .logWithMarker(
              name = "esl-freeswitch-ingest",
              e =>
                LogMarker(
                  name = "esl-freeswitch-ingest",
                  properties = Map(
                    "element" -> e,
                    "connection" -> fsConnection.getConnectionId,
                    "CallID" -> callId
                  )
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
            .addAttributes(ActorAttributes.supervisionStrategy(decider))
            .runWith(source, sink)
        }

        onFsConnectionStart(
          callId,
          connection,
          fsConnection,
          upStreamCompletion,
          downStreamStarted
        )

        adapter
          .info(
            fsConnection.logMarker,
            s"CALL-ID $callId FS-INTERFACE Id ${fsConnection.getConnectionId} FS Inbound Connection [remote address ${connection.remoteAddress}] - Connection Success"
          )

        downStreamStarted.onComplete {
          case Success(_) =>
            adapter.info(
              fsConnection.logMarker,
              s"CALL-ID $callId FS-INTERFACE Id ${fsConnection.getConnectionId} FS Inbound Connection [remote address ${connection.remoteAddress}] Downstream has been started successfully"
            )
          case Failure(ex: NeverMaterializedException) =>
            adapter.debug(
              fsConnection.logMarker,
              s"CALL-ID $callId FS-INTERFACE Id ${fsConnection.getConnectionId} FS Inbound Connection [remote address ${connection.remoteAddress}] Downstream start failure with Never materialized exception"
            )
          case Failure(ex) =>
            adapter.error(
              fsConnection.logMarker,
              s"CALL-ID $callId FS-INTERFACE Id ${fsConnection.getConnectionId} FS Inbound Connection [remote address ${connection.remoteAddress}] Downstream start failure with error ${ex.getMessage}",
              ex
            )
        }

        upStreamCompletion.onComplete {
          case Success(_) =>
            adapter.info(
              fsConnection.logMarker,
              s"CALL-ID $callId FS-INTERFACE Id ${fsConnection.getConnectionId} FS Inbound Connection [remote address ${connection.remoteAddress}] Upstream has been closed successfully"
            )
          case Failure(ex) =>
            adapter.error(
              fsConnection.logMarker,
              s"CALL-ID $callId FS-INTERFACE Id ${fsConnection.getConnectionId} FS Inbound Connection [remote address ${connection.remoteAddress}] Upstream closed with error ${ex.getMessage}",
              ex
            )
        }
      }
    }

    Tcp()
      .bind(address, port, backlog = 1000, idleTimeout = 20 seconds)
      .to(sink)
      .run()
  }
}
