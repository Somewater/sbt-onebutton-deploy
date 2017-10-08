package tasks

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.file
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import com.jcraft.jsch.OpenSSHConfig
import com.typesafe.config.{Config, ConfigFactory, ConfigValueType}
import fr.janalyse.ssh.{SSH, _}
import DeployTask.ServerContext
import DeployOpts._

import scala.collection.JavaConverters._
import DeployTask._
import sbt.Logger

import scala.util.control.NoStackTrace

/**
  * Seamless deploy
  */
object DeployTask {
  var logger: Logger = _

  def start(logger: Logger,
            stage: String,
            confs: Seq[File],
            baseDir: File,
            distFile: File): Unit = {
    this.logger = logger
    if (!distFile.exists()) {
      logger.error("Dist file not exists")
    } else if (confs.isEmpty) {
      logger.error("Config file list is empty")
    } else if (confs.exists(!_.exists())) {
      logger.error(s"Deploy config files not found: ${confs.filter(!_.exists()).mkString(", ")}'.\n" +
        "Try to create config file using task 'deployGenerateConf' or specify correct file using 'deployConf' setting key")
    } else {
      val config = confs.foldLeft(ConfigFactory.empty()) { case (config, confFile) ⇒
        config.withFallback(ConfigFactory.parseFile(confFile))
      }.resolve()
      if (config.hasPath("deploy")) {
        val path = s"deploy.$stage"
        if (config.hasPath(path)) {
          startWithConfig(config.getConfig(path), baseDir, distFile)
        } else {
          logger.error(s"Stage $stage not found in config")
        }
      } else {
        logger.error("Config empty or does not contain 'deploy' path")
      }
    }
  }

  def startWithConfig(config: Config, baseDir: File, distFile: File) {
    def getString(c: Config, key: String): Option[String] =
      if (c.hasPath(key)) Some(c.getString(key))
      else None


    val projectName = config.getString("project-name")
    val userHome = System.getProperty("user.home")
    val password =
      if (config.hasPath("ssh-identity-password")) {
        config.getString("ssh-identity-password")
      } else {
        val c = System.console
        if (c != null) c.readPassword("Type ssh identity password: ").mkString("").trim
        else {
          print("Type ssh identity password: ")
          new BufferedReader(new InputStreamReader(System.in)).readLine()
        }
      }

    val serversConf = config.getObject("servers").entrySet().asScala.map {
      case entry =>
        val shardName = entry.getKey
        val conf = config.getConfig(s"servers.$shardName")
        val commandsConf = conf.getConfig("commands")
        val commands = Commands(
          commandsConf.getString("start"),
          commandsConf.getString("stop"),
          getString(commandsConf, "restart"))
        val sConf = ServerConf(
          host = conf.getString("host"),
          commands = commands,
          username = getString(conf, "user"),
          deployPath = getString(conf, "path").getOrElse(s"~/$projectName/$projectName"),
          onCompleteScript = getString(conf, "on_complete_script"))
        shardName -> sConf
    }.toSeq.sortBy(_._1).toMap

    val sharedDirs = config.getObject("shared").entrySet().asScala.map {
      case entry =>
        val conf = config.getConfig(s"shared.${entry.getKey}")
        entry.getKey -> SharedDir(conf.getString("directory"), conf.getString("path"))
    }.toSeq.sortBy(_._1).toMap

    val opts = DeployOpts(
      paths = Paths(
        projectRoot = baseDir.getAbsolutePath,
        distFile = distFile.getAbsolutePath,
        deployPath = s"~/${projectName}/${projectName}",
        userHome = userHome),
      servers = serversConf,
      projectName = projectName,
      identityPassword = password,
      sharedDirs = sharedDirs)
    val deployHandler = new DeployHandler(opts)
    deployHandler.start()
  }

  case class ServerContext(title: String, homedir: String, conf: ServerConf, ssh: SSH) {
    def exec(cmd0: String): String = {
      val cmd = cmd0.replace("~", homedir)
      log(Console.YELLOW, s"Run on $title: " + Console.BLUE + cmd)
      val output = ssh.execOnce(cmd)
      if (output.trim.nonEmpty) log(Console.GREEN, output)
      output
    }

    def close() = {
      ssh.close()
    }
  }

  def log(color: String, msg: Any) = {
    logger.info(color + msg.toString + Console.RESET)
  }
}

case class DeployOpts(paths: Paths,
                      servers: Map[String, ServerConf],
                      projectName: String,
                      identityPassword: String,
                      sharedDirs: Map[String, SharedDir])
object DeployOpts {
  case class Paths(projectRoot: String, distFile: String, deployPath: String, userHome: String)
  case class ServerConf(host: String, commands: Commands, username: Option[String], deployPath: String,
                        onCompleteScript: Option[String])
  case class Commands(start: String, stop: String, restart: Option[String])
  case class SharedDir(directory: String, path: String)
}

class DeployHandler(opts: DeployOpts) {

  val sshConfig = OpenSSHConfig.parseFile(s"${opts.paths.userHome}/.ssh/config")
  val formatter = DateTimeFormatter.ofPattern("YYYYMMddHHmmss")
  lazy val serverContexts = opts.servers.map { case (name, conf) => createConnectionAndContext(name, conf) }

  lazy val currentRelease = {
    LocalDateTime.now().format(formatter)
  }

  def releasePath(implicit s: ServerContext) = s.conf.deployPath + s"/releases/$currentRelease"
  def tmpPath(implicit s: ServerContext) = s.conf.deployPath + s"/tmp"
  def currentPath(implicit s: ServerContext) = s.conf.deployPath + s"/current"
  def zipRemotePath(implicit s: ServerContext) = s.conf.deployPath + s"/tmp/$currentRelease.zip"

  def start() = {
    try {
      serverContexts.foreach {
        implicit context =>
          val startAt = System.currentTimeMillis()
          log(Console.CYAN_B, s"Start deploying to host ${context.title}")

          deployServer()

          val durationSec = ((System.currentTimeMillis() - startAt) / 1000.0).formatted("%.1f")
          log(Console.CYAN_B, s"Server ${context.title} deployed in ${durationSec} seconds")
      }
    } finally {
      serverContexts.foreach(_.close())
    }
  }

  def createConnectionAndContext(serverName: String, server: ServerConf): ServerContext = {
    val hostConfig = sshConfig.getConfig(server.host)
    val host = Option(hostConfig.getHostname).getOrElse(server.host)
    val identityFile = Option(hostConfig.getValue("IdentityFile"))
      .getOrElse(s"~/.ssh/id_rsa")
      .replace("~", opts.paths.userHome)
    val user = server.username.getOrElse(Option(hostConfig.getUser).getOrElse(opts.projectName))
    val port = if (hostConfig.getPort > 0) hostConfig.getPort else 22
    val password = SSHPassword(Some(opts.identityPassword))

    val serverTitle = host + (if (host == serverName) "" else s" (${serverName})")
    log(Console.YELLOW, s"Start deploying to host ${serverTitle}")

    val ssh =
      SSH.apply(SSHOptions(
        host = host,
        username = user,
        identities = List(SSHIdentity(identityFile, password)),
        port = port))
    log(Console.YELLOW, s"Connected to ${serverTitle}")

    val remoteHomedir = ssh.execOnce("echo $HOME")
    ServerContext(serverTitle,
      remoteHomedir,
      server.copy(deployPath = server.deployPath.replace("~", remoteHomedir)),
      ssh)
  }

  def deployServer()(implicit serverContext: ServerContext) = {
    createProjectStructure()
    uploadRelease()
    restartServer()
    onComplete()
  }

  /**
    * Create capistrano-like strucutre
    *
    * ├── current -> /var/www/my_app_name/releases/20150120114500/
    * ├── releases
    * │   ├── 20150080072500
    * │   ├── 20150090083000
    * ├── tmp
    * │   └── 20150080072500.zip
    * ├── revisions.log
    * └── shared
    *     └── <linked_files and linked_dirs>
    */
  def createProjectStructure()(implicit s: ServerContext): Unit = {
    s.exec(s"mkdir -p ${s.conf.deployPath}/releases")
    s.exec(s"mkdir -p ${s.conf.deployPath}/tmp")
    s.exec(s"mkdir -p ${s.conf.deployPath}/shared")
  }

  def uploadRelease()(implicit s: ServerContext) = {
    val rootFolderName = getBaseName(opts.paths.distFile)
    val scp = new SSHScp()(s.ssh)
    try {
      s.exec(s"rm -rf $tmpPath/$rootFolderName")

      log(Console.YELLOW, s"Upload build to ${s.title}...")
      scp.send(opts.paths.distFile, zipRemotePath)
      log(Console.YELLOW, s"Upload completed")

      s.exec(s"unzip -q $zipRemotePath -d $tmpPath")
      s.exec(s"mv $tmpPath/$rootFolderName $releasePath")
      opts.sharedDirs.foreach {
        case (name, dir) =>
          s.exec(s"ln -s ${dir.path} $releasePath/${dir.directory}")
      }
      s.exec(s"rm $zipRemotePath")
      s.exec(s"rm $currentPath")
      s.exec(s"ln -s $releasePath $currentPath")
    } finally {
      scp.close()
    }
  }

  def restartServer()(implicit s: ServerContext) = {
    s.conf.commands.restart match {
      case Some(restart) =>
        s.exec(restart)
      case None =>
        s.exec(s.conf.commands.stop)
        s.exec(s.conf.commands.start)
    }
  }

  def onComplete()(implicit s: ServerContext) = {
    s.conf.onCompleteScript.foreach { script =>
      s.exec(
        s"""
           |export RELEASE_PATH=$releasePath
           |export RELEASE=$currentRelease
           |$script
        """.stripMargin)
    }
  }

  private def getBaseName(filepath: String): String = {
    val filepath = ""
    val filename = new File(filepath).getName
    val index = filename.lastIndexOf(".")
    if (index == -1) filename
    else filename.substring(0, index)
  }
}
