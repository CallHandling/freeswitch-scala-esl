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

import esl.domain._
import esl.util.StringHelpers

import scala.annotation.tailrec

object DefaultParser extends Parser {
  override def parse(text: String): (List[FSMessage], String) = {
    /**
      * This inner function does the split of incoming string using \n\n as delimiter.
      *
      * @param inpString is the input string to split
      * @param delim     \n\n is the delimiter
      * @param accum     this is the accumulator List to contain the partial output
      * @param depth     this is set to 3 now as the messages can have up to 3 section - header, header 1 and body
      * @return returns the list of string with their length so the calling function can start interpreting the structure
      */
    @tailrec
    def splitIncomingString(inpString: String, delim: String, accum: List[(String, Int)], depth: Int): List[(String, Int)] = {

      if (depth == 0) (inpString, inpString.length) :: accum
      else
        inpString.indexOf(delim) match {
          case -1 => (inpString, inpString.length) :: accum
          case x: Int =>
            val (left, right) = inpString.splitAt(x + 1)
            splitIncomingString(right, delim, (left, x + 1) :: accum, depth - 1)
        }
    }

    /**
      * This function gets the input string and sends it to splitIncomingString to get the list of strings first. It then
      * analyzes the list by iterating it and finding out the valid Event. Any string that is not able to interpret is
      * returned back
      *
      * @param textRemaining the string that has to be fitted in to Message objects
      * @return returns the tuple of 1. the string that cant be interpreted 2. The message that is properly parsed and a
      *         boolean to tell are there any string left to interpret.
      */
    def parseBasicMessage(textRemaining: String): (String, Option[BasicMessage], Boolean) = {
      val listOfThings = splitIncomingString(textRemaining, "\n\n", Nil, 3).reverse

      listOfThings match {
        case Nil => (textRemaining, None, true)
        case header :: Nil => (header._1, None, true)
        case header :: rest =>
          val hdr = StringHelpers.parseKeyValuePairs(header._1, ':')
          val contentLength = hdr.getOrElse("Content-Length", "-1").toInt

          if (contentLength != -1) {
            val foldedResult = rest.foldLeft(("", "", 0)) { (accum, elem) => {
              if (accum._3 == -1) (accum._1, accum._2 + elem._1, accum._3)
              else if (elem._2 + accum._3 == contentLength) (accum._1 + elem._1, accum._2, -1)
              else (accum._1 + elem._1, accum._2, accum._3 + elem._2)
            }
            }
            if (foldedResult._3 == -1) {
              (foldedResult._2, Some(BasicMessage(hdr, Some(foldedResult._1.tail))), foldedResult._2.isEmpty)
            } else {
              (textRemaining, None, true)
            }
          } else {
            val curTextRemaining = textRemaining.substring(header._2)
            (curTextRemaining, Some(BasicMessage(hdr, None)), curTextRemaining.isEmpty)
          }
      }
    }

    /**
      * This function will convert event messages and also continue parsing BasicMessages if there is text still remaining
      *
      * @param doTxt This is the text left to process
      * @param msgs  This is the list of messages found in the text so far
      * @return Returns an Option of List of FreeSwitchMessage and any remaining data that could not be processed
      */
    @tailrec
    def doParse(doTxt: String, msgs: List[FSMessage]): (List[FSMessage], String) = {
      import scala.language.postfixOps
      val k = parseBasicMessage(doTxt)
      k match {
        case (t, None, f) if f =>
          if (msgs isEmpty) (Nil, t) else (msgs, t)
        case (t, None, f) if !f => doParse(t, msgs)
        case (t, Some(x), f) if !f && x.contentType == ContentTypes.eventPlain =>
          doParse(t, EventMessage(x) :: msgs)
        case (t, Some(x), f) if !f && x.contentType == ContentTypes.commandReply &&
          !x.headers.contains(HeaderNames.eventName) =>
          doParse(t, CommandReply(x) :: msgs)
        case (t, Some(x), _) =>
          doParse(t, x :: msgs)
      }
    }

    doParse(text, List.empty)
  }
}
