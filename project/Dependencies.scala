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

  lazy val logback = Seq(
    "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingV,
    "ch.qos.logback" % "logback-classic" % logbackV
  )

  val scalaTest = Seq(
    "org.scalatest" %% "scalatest" % scalaTestV % Test,
    "org.mockito" % "mockito-all" % mockitoV % Test,
    "org.scalacheck" %% "scalacheck" % scalaCheckV % Test
  )
}
