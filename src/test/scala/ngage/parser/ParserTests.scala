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

package ngage.parser

import ngage.domain.ContentTypes
import org.scalatest.{FlatSpec, Matchers}

class ParserTests extends FlatSpec with Matchers {
  behavior of "A Parser"

  it must "generate a valid EventMessage for BackgroundJob" in {
    val (ls, _) = DefaultParser.parse(TestMessages.backgroundJob)
    ls.map(_.contentType) should be(List(ContentTypes.eventPlain))
    ls.map(_.contentLength) should be(List(41))
    ls.map(_.body.map(_.length)) should be(List(Some(41)))
  }

  it must "generate a valid BasicMessage for ConnectEvent" in {
    val (ls, _) = DefaultParser.parse(TestMessages.connectEvent)
    ls.map(_.headers.size) should be(List(163))
    ls.map(_.contentLength) should be(List(0))
    ls.map(_.contentType) should be(List(ContentTypes.commandReply))
  }

  it must "parse multiple messages to a list of correct types" in {
    val (ls, _) = DefaultParser.parse(TestMessages.callState + TestMessages.disconnectEvent)
    ls.map(_.contentLength) should be(List(68, 1751))
    ls.map(_.headers.size) should be(List(4, 52))
    ls.map(_.contentType) should be(List(ContentTypes.disconnectNotice, ContentTypes.eventPlain))
    ls.map(_.getClass.getName) should be(List("ngage.domain.BasicMessage", "ngage.domain.EventMessage"))
  }

  it must "parse a partial messages across multiple calls to parse" in {
    val txt = TestMessages.backgroundJob + TestMessages.callState + TestMessages.disconnectEvent
    for (i <- 50 to txt.length by 50) {
      val p1 = DefaultParser.parse(txt.substring(0, i))
      val p2 = DefaultParser.parse(p1._2 + txt.substring(i))
      val bm = p1._1 ++ p2._1
      bm.map(_.contentType) should contain allElementsOf List("text/event-plain", "text/disconnect-notice", "text/event-plain")
      bm.map(_.headers.size) should contain allElementsOf List(16, 52, 4)
    }
  }
}
