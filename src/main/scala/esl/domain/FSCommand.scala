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
import esl.domain.EventOutputFormats.EventOutputFormat
import esl.domain.EventNames.EventName

import scala.concurrent.Future

sealed trait FSCommand

sealed trait FSExecuteApp extends FSCommand {
  val config: ApplicationCommandConfig
  val eventUuid: String = java.util.UUID.randomUUID.toString
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

  case class AuthCommand(password: String) extends FSCommand {
    override def toString: String = s"auth $password\n\n"
  }

  case class ConnectCommand(auth: String) extends FSCommand{
    override def toString: String = s"connect\n\n$auth\n\n"
  }

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
    * @param events       : List[EventName]
    * @param eventFormats : EventOutputFormat it could be `plain`,`xml` or `json`
    */
  case class SubscribeEvents(events: List[EventName], eventFormats: EventOutputFormat) extends FSCommand {
    override def toString: String = s"event ${eventFormats.name} ${events.map(_.name).mkString(" ")}"
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
    * @param uuid         : String
    * @param eventFormats : EventOutputFormat `plain`,`xml` or `json`
    */
  case class SubscribeMyEvents(uuid: String, eventFormats: EventOutputFormat) extends FSCommand {
    override def toString: String = s"myevents ${eventFormats.name} $uuid"
  }

  case class CommandRequest(command: FSCommand, queueOfferResult: Future[QueueOfferResult])

}