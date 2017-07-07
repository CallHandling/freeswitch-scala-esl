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

object AnswerStates {

  sealed trait AnswerState {
    val name: String
  }

  case object NewChannel extends AnswerState {
    override val name: String = "new_channel"
  }

  case object Early extends AnswerState {
    override val name: String = "early"
  }

  case object Answered extends AnswerState {
    override val name: String = "answered"
  }

  case object Hangup extends AnswerState {
    override val name: String = "hangup"
  }

  case object Ringing extends AnswerState {
    override val name: String = "ringing"
  }

  val states: Map[String, AnswerState] = List(NewChannel, Early, Answered, Hangup, Ringing)
    .map(state => state.name -> state).toMap
}
