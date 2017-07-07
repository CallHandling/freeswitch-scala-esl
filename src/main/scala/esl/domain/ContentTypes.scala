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

object ContentTypes {
  val eventPlain = "text/event-plain"
  val eventJson = "text/event-json"
  val eventXml = "text/event-xml"
  val authRequest = "auth/request"
  val commandReply = "command/reply"
  val apiResponse = "api/response"
  val disconnectNotice = "text/disconnect-notice"
  val rudeRejection = "text/rude-rejection"
}
