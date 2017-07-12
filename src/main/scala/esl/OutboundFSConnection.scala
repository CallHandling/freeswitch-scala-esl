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

import akka.Done
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, QueueOfferResult}
import akka.stream.scaladsl.Sink
import esl.domain.CallCommands.{AuthCommand, CommandRequest, SubscribeEvents}
import esl.domain.EventNames.EventName
import esl.domain.EventOutputFormats.{EventOutputFormat, Plain}
import esl.domain.{EventMessage, FSMessage}
import esl.parser.Parser

import scala.concurrent.Future

case class OutboundFSConnection(parser: Parser)(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer)
  extends FSConnection {
  override implicit val system: ActorSystem = actorSystem
  override implicit val materializer: ActorMaterializer = actorMaterializer

  override def connect(password: String): Future[QueueOfferResult] = queue.offer(AuthCommand(password))
  /**
    * Enable or disable events by class or all (plain or xml or json output format)
    *
    * @param events       : EventName* specify any number of events
    * @param eventFormats : EventFormat plain or xml or json output format
    * @return CommandRequest
    */
  def subscribeEvents(events: EventName*)(eventFormats: EventOutputFormat = Plain): CommandRequest = {
    val command = SubscribeEvents(events.toList, eventFormats)
    CommandRequest(command, queue.offer(command))
  }

  def where(fun: EventMessage => Boolean)(handleEvents: EventMessage => Unit): Sink[List[FSMessage], Future[Done]] = {
    Sink.foreach[List[FSMessage]] { fsMessages =>
      fsMessages.collect {
        case eventMsg: EventMessage if fun(eventMsg) => handleEvents(eventMsg)
      }
    }
  }
}