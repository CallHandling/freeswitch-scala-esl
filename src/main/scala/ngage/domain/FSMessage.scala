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

import ngage.util._

trait FSMessage extends FSBridgeCommand {
  val headers: Map[String, String]
  val body: Option[String]
  val contentType: String
  val contentLength: Int
}

case class BasicMessage(headers: Map[String, String], body: Option[String]) extends FSMessage {
  val contentType = headers.get(HeaderNames.contentType) match {
    case Some(ct) => ct
    case None => ""
  }
  val contentLength: Int = headers.get(HeaderNames.contentLength) match {
    case Some(cl) => cl.toInt
    case None => 0
  }
  //ToDo: Implement toString
}

case class CommandReply(basicMessage: BasicMessage) extends FSMessage {

  if (basicMessage.contentType != ContentTypes.commandReply) {
    throw new Error("Expected content type command/reply, got " + basicMessage.contentType + " instead.")
  }
  val headers = basicMessage.headers
  val body = basicMessage.body
  val replyText: String = headers(HeaderNames.replyText)

  val success = replyText != null && replyText.charAt(0) == '+'

  val errorMessage: String = if (replyText != null && replyText.startsWith("-ERR")) {
    replyText.substring(5, replyText.length - 5)
  } else ""

  override val contentLength: Int = basicMessage.contentLength
  override val contentType: String = basicMessage.contentType
}

case class ApiResponse(basicMessage: BasicMessage) extends FSMessage {
  val success: Boolean = basicMessage.body match {
    case Some(_) => true
    case None => false
  }
  val error: Option[String] = basicMessage.body match {
    case Some(body) if body.startsWith("-ERR") => Some(body.substring(5, body.length - 5))
    case None => None
  }
  val body = basicMessage.body
  val contentLength = basicMessage.contentLength
  val contentType = basicMessage.contentType
  val headers = basicMessage.headers
}

case class EventMessage(basicMessage: BasicMessage) extends FSMessage {

  import scala.language.postfixOps

  private val isCommandReply: Boolean = {
    if (basicMessage.contentType == ContentTypes.commandReply &&
      basicMessage.headers.contains(HeaderNames.eventName)) {
      true
    }
    else {
      false
    }
  }
  private val delimiterIndex: Int = {
    basicMessage.body match {
      case Some(bdy) => bdy.indexOf("\n\n")
      case _ => -1
    }
  }

  private val hasBody: Boolean = {
    basicMessage.body match {
      case Some(bdy) if basicMessage.headers.getOrElse("Content-Length", "-1").toInt > -1 =>
        !(delimiterIndex == -1 || delimiterIndex == bdy.length - 2)
      case _ => false
    }
  }

  val headers: Map[String, String] = basicMessage match {
    case bm if isCommandReply => bm.headers
    case bm => bm.body match {
      case Some(b) if !hasBody =>
        basicMessage.headers ++ StringHelpers.parseKeyValuePairs(b, ':')
      case Some(b) if hasBody =>
        basicMessage.headers ++ StringHelpers.parseKeyValuePairs(b substring(0, delimiterIndex), ':')
      case _ => bm.headers
    }
  }

  override val contentLength: Int = headers.get("Content-Length") match {
    case Some(x) => x toInt
    case None => -1
  }

  val body: Option[String] = basicMessage match {
    case bm if isCommandReply => bm.body
    case bm =>
      bm.body match {
        case Some(bdy) if !hasBody =>
          None
        case Some(bdy) if hasBody =>
          val b = bdy.substring(delimiterIndex + 2, bdy.length)
          Some(b)
        case _ =>
          None
      }
  }

  override val contentType: String = basicMessage.contentType

  val uuid = headers.get(HeaderNames.uniqueId)
  val otherLegUUID = headers.get(HeaderNames.otherLegUniqueId)
  val channelCallUUID = headers.get(HeaderNames.channelCallUniqueId)
  val eventName = headers.get(HeaderNames.eventName) match {
    case Some(name) => EventNames.events.lift(name)
    case _ => None
  }

  val channelState = headers.get(HeaderNames.channelState) match {
    case Some(name) => ChannelStates.states.lift(name)
    case _ => None
  }

  val answerState = headers.get(HeaderNames.answerState) match {
    case Some(name) => AnswerStates.states.lift(name)
    case _ => None
  }

  val hangupCause = headers.get(HeaderNames.hangupCause) match {
    case Some(name) => HangupCauses.causes.lift(name)
    case _ => None
  }


  def getHeader(header: String): Option[String] = headers.get(header)

  def getVariable(variable: String): Option[String] = getHeader("variable_" + variable)

  //ToDO implement toString for debugging
}