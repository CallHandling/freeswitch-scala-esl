import ReleaseTransformations._

lazy val commonSettings = Seq(
  moduleName := "freeswitch-scala-esl",
  organization := "uk.co.callhandling",
  name := "Freeswitch ESL",
  version := "1.1.8-SNAPSHOT",
  scalaVersion := "2.12.1",
  resolvers += "Apache Snapshots" at "https://repository.apache.org/content/repositories/snapshots/",
  libraryDependencies ++= Dependencies.scalaTest ++ Dependencies.log4j,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  pgpSecretRing := Path.userHome / ".gnupg/secring.gpg",
  pgpPublicRing := Path.userHome / ".gnupg/pubring.gpg",
  credentials += Credentials(Path.userHome / ".m2" / "sonatype-pgp.credentials"),
  credentials += Credentials(Path.userHome / ".m2" / "sonatype.credentials"),
  credentials += Credentials(Path.userHome / "github.credentials"),
  publishMavenStyle := true,
  pomIncludeRepository := { (repo: MavenRepository) =>
    repo.root.startsWith("file:")
  },
  skip in publish := true,
  useGpg := true,
  pomIncludeRepository := { _ => false },
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/CallHandling/freeswitch-scala-esl"),
      "git@github.com:CallHandling/freeswitch-scala-esl.git"
    )
  ),
  developers := List(
    Developer(
      id = "geekbytes.0xff",
      name = "mts.manu",
      email = "0xff@geekbytes.io",
      url = url("http://geekbytes.io")
    )
  ),
  licenses := Seq("Apache 2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  homepage := Some(url("https://github.com/CallHandling/freeswitch-scala-esl")),
  /*releaseProcess := Seq[ReleaseStep](
    releaseStepCommand(s"""sonatypeOpen "${organization.value}" "${name.value} v${version.value}""""),
    releaseStepCommand("publishSigned"),
    releaseStepCommand("sonatypeRelease")
  ),*/
  releaseUseGlobalVersion := false,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  releaseProcess := {
    releaseProcess.value.flatMap({
      case `publishArtifacts` => Seq(publishArtifacts, ReleaseStep(releaseStepCommand(s"""sonatypeOpen "${organization.value}" "${name.value} v${(version in ThisBuild).value}"""")),ReleaseStep(releaseStepCommand("sonatypeRelease")))
      case s => Seq(s)
    })
  }
)



lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Dependencies.akkaStream
  )
