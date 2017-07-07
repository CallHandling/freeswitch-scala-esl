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

object ChannelStates {

  sealed trait ChannelState {
    val name: String
  }

  case object NewChannel extends ChannelState {
    override val name: String = "CS_NEW"
  }

  case object Init extends ChannelState {
    override val name: String = "CS_INIT"
  }

  case object Routing extends ChannelState {
    override val name: String = "CS_ROUTING"
  }

  case object SoftExecute extends ChannelState {
    override val name: String = "CS_SOFT_EXECUTE"
  }

  case object Execute extends ChannelState {
    override val name: String = "CS_EXECUTE"
  }

  case object ExchangeMedia extends ChannelState {
    override val name: String = "CS_EXCHANGE_MEDIA"
  }

  case object Park extends ChannelState {
    override val name: String = "CS_PARK"
  }

  case object ConsumeMedia extends ChannelState {
    override val name: String = "CS_CONSUME_MEDIA"
  }

  case object Hibernate extends ChannelState {
    override val name: String = "CS_HIBERNATE"
  }

  case object Reset extends ChannelState {
    override val name: String = "CS_RESET"
  }

  case object Hangup extends ChannelState {
    override val name: String = "CS_HANGUP"
  }

  case object Done extends ChannelState {
    override val name: String = "CS_DONE"
  }

  case object Destroy extends ChannelState {
    override val name: String = "CS_DESTROY"
  }

  case object Reporting extends ChannelState {
    override val name: String = "CS_REPORTING"
  }

  val states: Map[String, ChannelState] = List(
    NewChannel, Init, Routing, SoftExecute, Execute, ExchangeMedia, Park, ConsumeMedia, Hibernate,
    Reset, Hangup, Done, Destroy, Reporting)
    .map(state => state.name -> state).toMap
}
