lazy val commonSettings = Seq(
  name := "freeswitch-scala-esl",
  version := "1.01",
  scalaVersion := "2.12.1",
  resolvers += "Apache Snapshots" at "https://repository.apache.org/content/repositories/snapshots/",
  libraryDependencies ++= Dependencies.scalaTest ++ Dependencies.log4j
)



lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Dependencies.akkaStream
  )
