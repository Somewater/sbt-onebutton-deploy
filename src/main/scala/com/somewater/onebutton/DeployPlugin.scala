package com.somewater.onebutton

import java.io.{BufferedReader, InputStreamReader}

import sbt._
import sbt.Keys._
import complete.DefaultParsers._
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.universal.UniversalPlugin
import tasks.DeployTask

object DeployPlugin extends AutoPlugin {
  override def trigger = allRequirements

  override def requires = UniversalPlugin

  object autoImport {
    val deployConfs = SettingKey[Seq[File]]("deployConf",
      "Path to deploy configuration files (in HOCON format). " +
        "You can separate stages by different files for convenience")
    val deployGenerateConf = TaskKey[Unit]("deployGenerateConf",
      "Generate default deploy conf file and put it as <project>/conf/deploy.conf")
    val deployDefaultStage = SettingKey[String]("deployDefaultStage", "Default stage")
    val deploy = InputKey[Unit]("deploy", "Deploy current release. Pass required stage as optional argument")
  }

  import autoImport._

  override lazy val projectSettings = Seq(
    deployConfs := Seq(baseDirectory.value / "conf" / "deploy.conf"),

    deployDefaultStage := "default",

    deploy := {
      val log = streams.value.log
      val distFile = dist.value
      val baseDir = baseDirectory.value
      val stages = spaceDelimited("<stage>").parsed
      if (stages.size > 1) {
        log.error("Too many arguments for task: " + stages.mkString(" "))
      } else {
        DeployTask.start(log,
          stages.headOption.getOrElse(deployDefaultStage.value),
          deployConfs.value,
          baseDir,
          distFile)
      }
    },

    deployGenerateConf := generateConf(streams.value.log,
      deployConfs.value.headOption.getOrElse(baseDirectory.value / "conf" / "deploy.conf"))
  )

  private def generateConf(log: Logger, toPath: File) {
    if (toPath.exists()) {
      log.warn(s"File $toPath already exists. Do you want to override it? (y/n) [n]")
      print("> ")
      val answer = new BufferedReader(new InputStreamReader(System.in)).readLine().trim.toLowerCase
      if (answer.isEmpty || answer.charAt(0) != 'y') {
        log.info("Task skipped")
        return
      }
    }
    toPath.getParentFile.mkdirs()
    IO.write(toPath, IO.readBytes(getClass.getClassLoader.getResourceAsStream("deploy.template.conf")))
    log.info("Deploy config successfully written to " + toPath)
  }
}
