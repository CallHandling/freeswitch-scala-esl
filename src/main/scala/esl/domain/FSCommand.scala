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

package esl.domain

import akka.stream.QueueOfferResult
import esl.domain.EventNames.EventName

import scala.concurrent.Future
import scala.concurrent.duration.Duration

sealed trait FSCommand {
  val eventUuid: String = java.util.UUID.randomUUID.toString
}

sealed trait FSExecuteApp extends FSCommand {
  val config: ApplicationCommandConfig
  val application: String
  val args = ""

  override def toString: String = {
    val b = StringBuilder.newBuilder
    b.append(s"sendmsg ${config.uuid}\nEvent-UUID: $eventUuid\ncall-command: execute\nexecute-app-name: $application\n")
    if (config.eventLock) b.append("event-lock: true\n")
    if (config.loops > 1) b.append(s"loops: ${config.loops}\n")
    if (config.async) b.append(s"async: ${config.async}\n")
    if (args.length > 0) b.append(s"content-type: text/plain\ncontent-length: ${args.length}\n\n$args\n")
    b.toString()
  }
}

case class ApplicationCommandConfig(uuid: String = "", eventLock: Boolean = false,
                                    loops: Int = 1, async: Boolean = false)

object CallCommands {

  case class None(config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "none"
  }

  case class Hangup(config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "hangup"
  }

  case class Break(config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "break"
  }

  case class PlayFile(filePath: String, config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "playback"
    override val args: String = filePath
  }

  case class TransferTo(extension: String, config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "transfer"
    override val args: String = extension
  }

  case class Answer(config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "answer"
  }

  case class AuthCommand(password: String) extends FSCommand {
    override def toString: String = s"auth $password\n\n"
  }

  case class SetVar(varName: String, varValue: String, config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "set"
    override val args: String = s"$varName=$varValue"
  }

  /**
    * att_xfer <channel_url>
    * Bridge a third party specified by channel_url onto the call, speak privately, then bridge original caller to target channel_url of att_xfer.
    *
    * @param destination   : String target channel_url of att_xfer
    * @param conferenceKey : Char "attxfer_conf_key" - can be used to initiate a three way transfer (deafault '0')
    * @param hangupKey     : Char "attxfer_hangup_key" - can be used to hangup the call after user wants to end his or her call (deafault '*')
    * @param cancelKey     : Char "attxfer_cancel_key" - can be used to cancel a tranfer just like origination_cancel_key, but straight from the att_xfer code (deafault '#')
    * @param config        : ApplicationCommandConfig
    */
  case class AttXfer(destination: String,
                     conferenceKey: Char,
                     hangupKey: Char,
                     cancelKey: Char,
                     config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "att_xfer"
    override val args: String = s"{attxfer_conf_key=$conferenceKey,attxfer_hangup_key=$hangupKey,attxfer_cancel_key=$cancelKey}$destination"
  }

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
    */
  case class Bridge(targets: List[String], dialType: DialType, config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "bridge"
    override val args: String = targets.mkString(dialType.separator)
  }

  case object ConnectCommand extends FSCommand {
    override def toString: String = s"connect\n\n"
  }

  /**
    * Freeswitch command as raw command that present as string
    *
    * @param command : String
    */
  case class CommandAsString(command: String) extends FSCommand {
    override def toString: String = command
  }

  /**
    * The event command are used to subscribe on events from FreeSWITCH
    * event plain ALL
    * event plain CHANNEL_CREATE CHANNEL_DESTROY CUSTOM conference::maintenance sofia::register sofia::expire
    * event xml ALL
    * event json CHANNEL_ANSWER
    *
    * @param events : List[EventName]
    */
  case class SubscribeEvents(events: List[EventName]) extends FSCommand {
    override def toString: String = s"event plain ${events.map(_.name).mkString(" ")}"
  }

  /**
    * The 'myevents' subscription allows your inbound socket connection to behave like an outbound socket connect.
    * It will "lock on" to the events for a particular uuid and will ignore all other events
    * myevents plain <uuid>
    * myevents json <uuid>
    * myevents xml <uuid>
    * Once the socket connection has locked on to the events for this particular uuid it will NEVER see any events
    * that are not related to the channel, even if subsequent event commands are sent
    *
    * @param uuid : String
    */
  case class SubscribeMyEvents(uuid: String) extends FSCommand {
    override def toString: String = s"myevents plain $uuid"
  }

  /**
    * Specify event types to listen for. Note, this is not a filter out but rather a "filter in," that is,
    * when a filter is applied only the filtered values are received. Multiple filters on a socket connection are allowed.
    * Usage:
    * filter <EventHeader> <ValueToFilter>
    *
    * @param events : Map[EventName, String] mapping of events and their value
    * @param config : ApplicationCommandConfig
    */
  case class Filter(events: Map[EventName, String], config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "filter"
    override val args: String = events.map { case (key, value) => s"${key.name} $value" }.mkString(" ")
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
    */
  case class DeleteFilter(events: Map[EventName, String], config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "filter delete"
    override val args: String = events.map { case (key, value) => s"${key.name} $value" }.mkString(" ")
  }

  /**
    * Allows one channel to bridge itself to the a or b leg of another call. The remaining leg of the original call gets hungup
    * Usage: intercept [-bleg] <uuid>
    *
    * @param uuid   : String
    * @param config :ApplicationCommandConfig
    */
  case class Intercept(uuid: String, config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "intercept"
    override val args: String = uuid
  }

  /**
    * Read DTMF (touch-tone) digits.
    * Usage
    * read <min> <max> <sound file> <variable name> <timeout> <terminators>
    * min = Minimum number of digits to fetch.
    * max = Maximum number of digits to fetch.
    * sound file = Sound file to play before digits are fetched.
    * variable name = Channel variable that digits should be placed in.
    * timeout = Number of milliseconds to wait on each digit
    * terminators = Digits used to end input if less than <min> digits have been pressed. (Typically '#')
    *
    * @param params : ReadParameters
    * @param config : ApplicationCommandConfig
    */
  case class Read(params: ReadParameters, config: ApplicationCommandConfig) extends FSExecuteApp {
    override val application: String = "read"
    override val args: String = List(
      params.min.toString, params.max.toString, params.soundFile,
      params.variableName, params.timeout.toMillis.toString,
      params.terminators.map(_.toString).mkString(","))
      .mkString(" ")
  }

  case class Phrase()
}

case class CommandRequest(command: FSCommand, queueOfferResult: Future[QueueOfferResult])

case class DialType(separator: String) extends AnyVal

/**
  *
  * @param min          Minimum number of digits to fetch.
  * @param max          Maximum number of digits to fetch.
  * @param soundFile    Sound file to play before digits are fetched.
  * @param variableName Channel variable that digits should be placed in.
  * @param timeout      Number of milliseconds to wait on each digit
  * @param terminators  Digits used to end input if less than <min> digits have been pressed. (Typically '#')
  */
case class ReadParameters(min: Int,
                          max: Int,
                          soundFile: String,
                          variableName: String,
                          timeout: Duration,
                          terminators: List[Char])