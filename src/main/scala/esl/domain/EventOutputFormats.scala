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

object EventOutputFormats {

  sealed trait EventOutputFormat extends Product with Serializable {
    val name: String
  }

  case object Plain extends EventOutputFormat {
    override val name: String = "plain"
  }

  case object Xml extends EventOutputFormat {
    override val name: String = "xml"
  }

  case object Json extends EventOutputFormat {
    override val name: String = "json"
  }

}
