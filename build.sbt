import sbtrelease.ReleaseStateTransformations._

lazy val commonSettings = Seq(
  moduleName := "freeswitch-scala-esl",
  organization := "uk.co.callhandling",
  name := "Freeswitch ESL",
  scalaVersion := "2.12.10",
  resolvers += "Apache Snapshots" at "https://repository.apache.org/content/repositories/snapshots/",
  libraryDependencies ++= Dependencies.scalaTest ++ Dependencies.log4j,

  credentials += Credentials(Path.userHome / "pgp.credentials"),
  credentials += Credentials(Path.userHome / "sonatype.credentials"),
  pgpSecretRing := Path.userHome / ".gnupg/secring.gpg",
  pgpPublicRing := Path.userHome / ".gnupg/pubring.gpg",
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
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
    )
  ),

  publishArtifact in Test := false,
  releaseUseGlobalVersion := false,
  licenses := Seq("Apache 2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  homepage := Some(url("https://github.com/CallHandling/freeswitch-scala-esl")),
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,              // : ReleaseStep
    inquireVersions,                        // : ReleaseStep
    runClean,                               // : ReleaseStep
    runTest,                                // : ReleaseStep
    setReleaseVersion,                      // : ReleaseStep
    commitReleaseVersion,                   // : ReleaseStep, performs the initial git checks
    tagRelease,                             // : ReleaseStep
    ReleaseStep(action = Command.process(s"""sonatypeOpen "${organization.value}" "${name.value} v${version.value}"""", _)),
    ReleaseStep(action = Command.process("publishSigned", _)),
    ReleaseStep(action = Command.process("sonatypeRelease", _)),
    setNextVersion,                         // : ReleaseStep
    commitNextVersion,                      // : ReleaseStep
    pushChanges

  )
)



lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Dependencies.akkaStream
  )
