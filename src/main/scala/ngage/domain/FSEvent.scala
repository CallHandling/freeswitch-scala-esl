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

import akka.actor.ActorRef

sealed trait FSEvent

case class CallUUIdCreated(callUUId: String) extends FSEvent

case class InterfaceRegistered(actorRef: ActorRef) extends FSEvent

case class MessageListChanged(messages: List[FSMessage]) extends FSEvent

case class BufferChanged(buffer: String) extends FSEvent

case class FlowActorRegistered(flow: ActorRef) extends FSEvent

case object CallCompletedSuccessfully extends FSEvent

case object CanNotFinishCall extends FSEvent

case object EventsUpdated extends FSEvent

case object MessageSentToInterface extends FSEvent

case class IgnoringTheMessage(message: FSMessage) extends FSEvent