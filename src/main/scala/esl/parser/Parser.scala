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

package esl.parser

import esl.domain.FSMessage

trait Parser {
  /**
    * This function should parse the text from the event socket and return a List of FreeSwitchMessages (Either BasicMessage or EventMessage)
    *
    * @param text The utf8 text coming in from the event socket
    * @return A list of generic FreeSwitchMessages found in the txt
    */
  def parse(text: String): (List[FSMessage], String)
}
