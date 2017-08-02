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

import esl.util._

import scala.language.postfixOps

trait FSMessage extends FSBridgeCommand {
  val headers: Map[String, String]
  val body: Option[String]
  val contentType: String
  val contentLength: Int
}

case class BasicMessage(headers: Map[String, String], body: Option[String]) extends FSMessage {
  lazy val contentType: String = headers.lift(HeaderNames.contentType).fold("")(identity)
  lazy val contentLength: Int = headers.lift(HeaderNames.contentLength).fold(0)(_.toInt)
  //ToDo: Implement toString
}

case class CommandReply(basicMessage: BasicMessage) extends FSMessage {

  require(basicMessage.contentType == ContentTypes.commandReply, s"Expected content type command/reply, got ${basicMessage.contentType} instead.")

  override val headers: Map[String, String] = basicMessage.headers
  override val body: Option[String] = basicMessage.body
  override val contentLength: Int = basicMessage.contentLength
  override val contentType: String = basicMessage.contentType

  val replyText = headers.lift(HeaderNames.replyText)

  val success: Boolean = replyText.exists(f => f.charAt(0) == '+' || f == "%2BOK%0A")

  val errorMessage: String = replyText.collect {
    case text if text.startsWith("-ERR") => text.substring(5, text.length - 5)
  }.getOrElse("")

}

case class ApiResponse(basicMessage: BasicMessage) extends FSMessage {
  val success: Boolean = basicMessage.body.isDefined

  val errorMessage: Option[String] = basicMessage.body.collect {
    case bodyOp if bodyOp.startsWith("-ERR") => bodyOp.substring(5, bodyOp.length - 5)
  }

  override val body: Option[String] = basicMessage.body
  override val contentLength: Int = basicMessage.contentLength
  override val contentType: String = basicMessage.contentType
  override val headers: Map[String, String] = basicMessage.headers
}

case class EventMessage(basicMessage: BasicMessage) extends FSMessage {

  private val isCommandReply: Boolean = basicMessage.contentType == ContentTypes.commandReply &&
    basicMessage.headers.contains(HeaderNames.eventName)

  private val delimiterIndex: Int = basicMessage.body.fold(-1)(_.indexOf("\n\n"))

  private val hasBody: Boolean = basicMessage.body match {
    case Some(bdy) if basicMessage.headers.getOrElse(HeaderNames.contentLength, "-1").toInt > -1 =>
      !(delimiterIndex == -1 || delimiterIndex == bdy.length - 2)
    case _ => false
  }

  override val headers: Map[String, String] = {
    if (isCommandReply) basicMessage.headers
    else basicMessage.body.fold(basicMessage.headers) { b =>
      if (hasBody) basicMessage.headers ++ StringHelpers.parseKeyValuePairs(b substring(0, delimiterIndex), ':')
      else basicMessage.headers ++ StringHelpers.parseKeyValuePairs(b, ':')
    }
  }

  override val contentLength: Int = headers.get(HeaderNames.contentLength).fold(-1)(_.toInt)

  override val body: Option[String] = {
    if (isCommandReply) basicMessage.body
    else basicMessage.body.flatMap {
      bdy => if (hasBody) Some(bdy.substring(delimiterIndex + 2, bdy.length)) else None
    }
  }

  override val contentType: String = basicMessage.contentType

  val uuid: Option[String] = headers.get(HeaderNames.uniqueId)
  val otherLegUUID: Option[String] = headers.get(HeaderNames.otherLegUniqueId)
  val channelCallUUID: Option[String] = headers.get(HeaderNames.channelCallUniqueId)
  val eventName: Option[EventNames.EventName] =
    headers.get(HeaderNames.eventName).fold(Option.empty[EventNames.EventName])(EventNames.events.lift)

  val channelState: Option[AnswerStates.AnswerState] =
    headers.get(HeaderNames.channelState).fold(Option.empty[AnswerStates.AnswerState])(AnswerStates.states.lift)

  val answerState: Option[AnswerStates.AnswerState] =
    headers.get(HeaderNames.answerState).fold(Option.empty[AnswerStates.AnswerState])(AnswerStates.states.lift)

  val hangupCause: Option[HangupCauses.HangupCause] =
    headers.get(HeaderNames.hangupCause).fold(Option.empty[HangupCauses.HangupCause])(HangupCauses.causes.lift)

  val applicationUuid: Option[String] = headers.get(HeaderNames.applicationUuid)

  def getHeader(header: String): Option[String] = headers.get(header)

  def getVariable(variable: String): Option[String] = getHeader(s"variable_$variable")

  //ToDO implement toString for debugging
}