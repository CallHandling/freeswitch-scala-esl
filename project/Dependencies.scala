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

import sbt._


object Dependencies {

  import Versions._

  val akkaStream = Seq(
    "com.typesafe.akka" %% "akka-stream" % akkaStreamV,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaStreamV % Test
  )

  lazy val log4j = Seq(
    "log4j-api",
    "log4j-core",
    "log4j-slf4j-impl"
  ).map {
    "org.apache.logging.log4j" % _ % "2.8.2"
  } ++
    Seq(
      "com.lmax" % "disruptor" % "3.3.6",
      "org.apache.logging.log4j" %% "log4j-api-scala" % "11.0"
    )

  val scalaTest = Seq(
    "org.scalatest" %% "scalatest" % scalaTestV % Test,
    "org.mockito" % "mockito-all" % mockitoV % Test,
    "org.scalacheck" %% "scalacheck" % scalaCheckV % Test
  )
}
