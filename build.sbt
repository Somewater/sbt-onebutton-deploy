name := "sbt-onebutton-deploy"

organization := "com.somewater"

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
