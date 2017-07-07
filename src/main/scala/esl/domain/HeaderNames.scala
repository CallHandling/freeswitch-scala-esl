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

object HeaderNames {
  val contentLength = "Content-Length"
  val contentType = "Content-Type"
  val callerUniqueId = "Caller-Unique-ID"
  val application = "Application"
  val applicationData = "Application-Data"
  val applicationResponse = "Application-Response"
  val eventName = "Event-Name"
  val channelState = "Channel-State"
  val answerState = "Answer-State"
  val hangupCause = "Hangup-Cause"
  val eventSubclass = "Event-Subclass"
  val uniqueId = "Unique-ID"
  val otherLegUniqueId = "Other-Leg-Unique-ID"
  val channelCallUniqueId = "Channel-Call-UUID"
  val jobUUID = "Job-UUID"
  val replyText = "Reply-Text"
  val dtmfDigit = "DTMF-Digit"
  val destinationNumber = "Caller-Destination-Number"
  val callerNumber = "Caller-Caller-ID-Number"

  object Conference {
    val name = "Conference-Name"
    val size = "Conference-Size"
    val profileName = "Conference-Profile-Name"
    val conferenceUniqueId = "Conference-Unique-ID"
    val floor = "Floor"
    val video = "Video"
    val hear = "Hear"
    val speak = "Speak"
    val talking = "Talking"
    val muteDetect = "Mute-Detect"
    val memberId = "Member-ID"
    val memberType = "Member-Type"
    val energyLevel = "Energy-Level"
    val currentEnergy = "Current-Energy"
    val action = "Action"
  }

}
