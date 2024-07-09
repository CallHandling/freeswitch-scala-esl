import sbtrelease.ReleaseStateTransformations._

lazy val commonSettings = Seq(
  moduleName := "freeswitch-scala-esl",
  organization := "uk.co.callhandling",
  name := "Freeswitch ESL",
  scalaVersion := "2.12.10",
  resolvers += "Apache Snapshots" at "https://repository.apache.org/content/repositories/snapshots/",
  libraryDependencies ++= Dependencies.scalaTest ++ Dependencies.logback,
  credentials += Credentials(Path.userHome / "pgp.credentials"),
  credentials += Credentials(Path.userHome / "sonatype.credentials"),
  pgpSecretRing := Path.userHome / ".gnupg/secring.gpg",
  pgpPublicRing := Path.userHome / ".gnupg/pubring.gpg",
  publishTo := sonatypePublishToBundle.value,
  pomIncludeRepository := { (repo: MavenRepository) =>
    repo.root.startsWith("file:")
  },
  publishMavenStyle := true,

  scmInfo := Some(
    ScmInfo(
      url("https://github.com/CallHandling/freeswitch-scala-esl"),
      "scm:https://github.com/CallHandling/freeswitch-scala-esl.git"
    )
  ),
  developers := List(
    Developer(
      id = "geekbytes.0xff",
      name = "mts.manu",
      email = "0xff@geekbytes.io",
      url = url("http://geekbytes.io")
    ),
    Developer(
      id = "abdheshkumar",
      name = "Abdhesh Kumar",
      email = "abdhesh.mca@gmail.com",
      url = url("http://learnscala.co")
    ),
    Developer(
      id = "nathanleyton",
      name = "Nathan Leyton",
      email = "nathan@hubbub.ai",
      url = url("https://callhandling.co.uk")
    )
  ),
  publishConfiguration := publishConfiguration.value.withOverwrite(true),
  publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true),
  publishArtifact in Test := false,
  releaseUseGlobalVersion := false,
  licenses := Seq("Apache 2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  homepage := Some(url("https://github.com/CallHandling/freeswitch-scala-esl")),

)

scalacOptions ++= Seq(
  "-Xcheckinit", "-feature"
)

lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Dependencies.akkaStream
  )
