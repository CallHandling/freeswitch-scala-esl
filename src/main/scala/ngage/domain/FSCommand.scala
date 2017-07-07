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

package ngage.domain

import akka.stream.QueueOfferResult

import scala.concurrent.Future


sealed trait FSCommand {
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

  case class None(config: ApplicationCommandConfig = ApplicationCommandConfig()) extends FSCommand {
    override val application: String = "none"
  }

  case class Hangup(config: ApplicationCommandConfig = ApplicationCommandConfig()) extends FSCommand {
    override val application: String = "hangup"
  }

  case class Break(config: ApplicationCommandConfig = ApplicationCommandConfig()) extends FSCommand {
    override val application: String = "break"
  }

  case class PlayFile(filePath: String, config: ApplicationCommandConfig = ApplicationCommandConfig()) extends FSCommand {
    override val application: String = "playback"
    override val args: String = filePath
  }

  case class TransferTo(extension: String, config: ApplicationCommandConfig = ApplicationCommandConfig()) extends FSCommand {
    override val application: String = "transfer"
    override val args: String = extension
  }

  case class CommandAsString(command: String) extends FSCommand {
    override val application: String = "<none>"
    override val config: ApplicationCommandConfig = ApplicationCommandConfig()

    override def toString: String = command
  }

  case class CommandRequest(command: FSCommand, queueOfferResult: Future[QueueOfferResult])

}