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
import akka.stream.{ActorMaterializer, QueueOfferResult}
import esl.domain.CallCommands.{AuthCommand, SubscribeMyEvents}
import esl.domain.CommandReply

import scala.concurrent.Future

case class InboundFSConnection()(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer)
  extends FSConnection {
  override implicit val materializer: ActorMaterializer = actorMaterializer
  override implicit val system: ActorSystem = actorSystem

  /**
    * Send auth command to freeswitch
    *
    * @param password : String
    * @return
    */
  override def connect(password: String): Future[QueueOfferResult] = queue.offer(AuthCommand(password))

  /**
    * Subscribe for `myevents` with `uuid`
    * The 'myevents' subscription allows your inbound socket connection to behave like an outbound socket connect.
    * It will "lock on" to the events for a particular uuid and will ignore all other events, closing the socket
    * when the channel goes away or closing the channel when the socket disconnects and all applications have finished executing.
    *
    * @param uuid : String
    * @return CommandRequest
    */
  def subscribeEvents(uuid: String): Future[CommandReply] = publishCommand(SubscribeMyEvents(uuid))
}
