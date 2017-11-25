## sbt-onebutton-deploy

It's a SBT plugin for SBT project deployment. Steps of deployment:
1. compile project locally and package it to "zip" archive 
   (thanks to [sbt-native-packager](https://github.com/sbt/sbt-native-packager))
2. connect to remote server using SSH
3. make required file structure in app directory (in first time):
    ```
     ├── current -> ../releases/20150090083000/
     ├── releases
     │   ├── 20150090083000
     │   └── 20150080072500
     ├── tmp
     │   └── 20150090083000.zip
     └── shared
         └── <linked_files and linked_dirs>
    ```
4. copy obtained archive to remote server, extract it and invoke `stop/start` scripts
5. goto step 2 with next app server (if multiple server deploy configured) 

## Installing
1. Integrate with your SBT project:
    
    Add to `project/plugins.sbt`
    ```scala
    addSbtPlugin("com.github.somewater" % "sbt-onebutton-deploy" % "0.0.2")
    ```
    
    Add to `build.sbt`
    ```scala
    enablePlugins(DeployPlugin)
    ```

2. Generate deploy config from template, run:
    ```bash
    sbt deployGenerateConf
    ```
    
    Edit new config placed in `conf/deploy.conf`

3. Server setup:
    * required: create start/stop scripts for service, for example using [runit](http://smarden.org/runit/)
    * optional: create directory for project logs (or other release-shared files like local config etc.)

## Usage
1. Checkout VCS to required project state

1. Run deploy:
    ```bash
    sbt deploy
    ```
    
    Or run deploy on non-default stage:
    ```bash
    sbt "deploy production"
    ```

## Requirements
* Deployment tested on linux servers
* SSH access to remote servers with required permissions
* [unzip](http://www.info-zip.org/pub/infozip/) utility should be installed on all remote servers 
  (installed by default on most linux distributions)

## License
This project is licensed under the terms of the MIT license.