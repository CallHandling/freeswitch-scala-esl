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

/**
  * Shared trait for all things that can be passed through a service
  */
trait WorkItem {}

case class Dummy(uuid: String) extends WorkItem

/**
  *
  * @param uuid    The uuid of the call
  * @param ref     The ActorRef of the actor managing the underlying communication with a media and signalling server
  * @param fromCli The cli of the person calling
  * @param toCli   The cli of the destination - Used for matching services
  */
case class Call(uuid: String, ref: ActorRef, fromCli: String, toCli: String) extends WorkItem