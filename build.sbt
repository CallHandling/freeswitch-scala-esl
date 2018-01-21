lazy val commonSettings = Seq(
  moduleName := "freeswitch-scala-esl",
  organization := "uk.co.callhandling",
  name := "Freeswitch ESL",
  version := "1.01",
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
  credentials += Credentials("Sonatype Nexus Repository Manager",
    "oss.sonatype.org", "", ""),
  publishMavenStyle := true,
  pomIncludeRepository := { (repo: MavenRepository) =>
    repo.root.startsWith("file:")
  },
  useGpg := true
  , pomIncludeRepository := { _ => false }
  , scmInfo := Some(
    ScmInfo(
      url("https://github.com/CallHandling/freeswitch-scala-esl"),
      "scm:https://github.com/CallHandling/freeswitch-scala-esl.git"
    )
  )
  //,gpgCommand := "C:\\Program Files (x86)\\Gpg4win\\bin\\gpa.exe"
  , developers := List(
    Developer(
      id = "0xff.geekbytes",
      name = "mts.manu",
      email = "0xff@geekbytes.io",
      url = url("http://geekbytes.io")
    )
  )
  , licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php"))

  , homepage := Some(url("https://github.com/CallHandling/freeswitch-scala-esl"))
  , releaseProcess := Seq[ReleaseStep](
    releaseStepCommand(s"""sonatypeOpen "${organization.value}" "${moduleName.value}""""),
    releaseStepCommand("publishSigned"),
    releaseStepCommand("sonatypeRelease")
  )
)



lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Dependencies.akkaStream
  )
