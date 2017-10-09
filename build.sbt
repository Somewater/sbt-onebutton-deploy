name := "sbt-onebutton-deploy"

organization := "com.github.somewater"

version := "0.0.1"

scalaVersion in Global := "2.10.6"

sbtPlugin := true

libraryDependencies ++= Seq(
  "fr.janalyse" %% "janalyse-ssh" % "0.10.3",
  "com.typesafe" % "config" % "1.3.+"
)

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(UniversalPlugin)

val deploy = TaskKey[Unit]("deploy", "Seamless deploy")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.2.0")

licenses := Seq("MIT License" -> url("http://opensource.org/licenses/MIT"))

homepage := Some(url("http://github.com/Somewater/sbt-onebutton-deploy"))

scmInfo := Some(
  ScmInfo(
    url("http://github.com/Somewater/sbt-onebutton-deploy"),
    "scm:git@github.com:Somewater/sbt-onebutton-deploy.git"
  )
)

developers := List(
  Developer(
    id    = "Somewater",
    name  = "Pavel Naydenov",
    email = "naydenov.p.v@gmail.com",
    url   = url("http://github.com/Somewater")
  )
)

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { _ => false }
