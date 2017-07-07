lazy val commonSettings = Seq(
  name := "freeswitch-scala-esl",
  version := "1.0",
  scalaVersion := "2.12.1",
  libraryDependencies ++= Dependencies.scalaTest
)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Dependencies.akkaStream
  )
