import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.decodeFromStream
import com.github.dockerjava.api.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.hc.client5.http.HttpHostConnectException
import java.io.File
import java.io.FileInputStream
import java.lang.IllegalStateException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Paths

var uid: Int = -1
var gid: Int = -1

fun main(args: Array<String>): Unit = runBlocking {

    val command = args[0]
    val arguments = parseArguments(args)
    println(command)
    println(arguments)

    val oarNodeFile = System.getenv("OAR_NODE_FILE")
    val hosts = mutableListOf<String>()
    File(oarNodeFile).forEachLine { if (!hosts.contains(it)) hosts.add(it) }
    val me = InetAddress.getLocalHost().hostName
    if (hosts.contains(me)) hosts.remove(me)
    else throw IllegalStateException("Hostname $me not found in OAR_NODE_FILE")

    println("I am $me")
    println("Hosts are: $hosts")

    val currentDir = Paths.get(System.getProperty("user.dir"))
    uid = Files.getAttribute(currentDir, "unix:uid") as Int
    gid = Files.getAttribute(currentDir, "unix:gid") as Int
    //println("$uid $gid")

    when (command) {
        "setup" -> setup(hosts, arguments)
        "run" -> run(hosts, arguments)
        "purge" -> purge(hosts)
        "interrupt" -> interrupt(hosts)
        else -> throw IllegalArgumentException("Unknown command: $command")
    }

}

suspend fun setup(hosts: List<String>, arguments: Map<String, String>): List<DockerProxy> {

    if (!arguments.containsKey("config_file")) throw IllegalArgumentException("Missing argument: config_file")
    println("Parsing configuration file ${arguments["config_file"]}")

    val config = Yaml.default.decodeFromStream<Config>(FileInputStream(arguments["config_file"]!!)).setup

    if (hosts.size * config.maxContainersPerMachine < config.nContainers) throw IllegalStateException(
        "Not enough hosts to run ${config.nContainers} containers, with a maximum of " + "${config.maxContainersPerMachine} containers per node"
    )

    val nLinesTc = Files.lines(Paths.get(config.tcFolder, config.latencyFile)).count()
    if (nLinesTc < config.nContainers) throw IllegalStateException("Not enough lines in ${config.latencyFile} to run ${config.nContainers} nodes")

    println("--- Checking docker status...")

    try {
        val client = DockerProxy(hosts[0])
        println("Docker seems to be usable")
        client.close()

    } catch (e: Exception) {
        if (ExceptionUtils.getRootCause(e) is HttpHostConnectException) {
            println("--- Docker is not running on ${hosts[0]}, will try to install on all hosts")
            Thread.sleep(3000)
            DockerProxy.gridInstallDockerParallel(hosts)
        } else throw e
    }

    println("--- Creating clients")
    val proxies = hosts.map { DockerProxy(it) }

    purge(hosts, proxies)

    DockerProxy.restartDockerService(hosts)

    proxies.forEach { it.reconnect() }

    println("--- Setting up swarm and network")
    val tokens = proxies[0].initSwarm()
    proxies.drop(1).forEach { it.joinSwarm(tokens, proxies[0].shortHost) }
    if (proxies[0].getSwarmMembers().size != proxies.size) throw IllegalStateException("Swarm members are not equal to the number of hosts")

    val networks = proxies[0].listNetworks()
    if (networks.contains(config.networkName)) {
        println("Network ${config.networkName} already exists, will not create it")
    } else {
        println("Creating overlay network ${config.networkName}")
        proxies[0].createOverlayNetwork(config.networkName, config.subnet, config.gateway)
    }

    println("--- Loading images")
    coroutineScope {
        proxies.map {
            async(Dispatchers.IO) {
                it.loadImage(config.imageLoc)
            }
        }.joinAll()
    }

    println("--- Creating containers")

    val containersInfo = mutableMapOf<String, MutableList<Pair<String, String>>>()
    proxies.forEach { containersInfo[it.shortHost] = mutableListOf() }

    val ips = File("tc/ips.txt").readLines()

    for (containerNumber in config.nContainers - 1 downTo 0) {
        val proxy = proxies[containerNumber % proxies.size]
        containersInfo[proxy.shortHost]!!.add(Pair(containerNumber.toString(), ips[containerNumber]))
    }

    val modulesVol = Volume("/lib/modules")
    val logsVol = Volume("/logs")
    val tcVol = Volume("/tc")
    val codeVol = Volume("/code")

    val volumes = Volumes(modulesVol, logsVol, tcVol, codeVol)

    val binds = Binds(
        Bind("/lib/modules", modulesVol),
        Bind(config.logsFolder, logsVol),
        Bind(config.tcFolder, tcVol, AccessMode.ro),
        Bind(config.codeFolder, codeVol, AccessMode.ro)
    )
    val hostConfig = HostConfig().withAutoRemove(true).withPrivileged(true).withCapAdd(Capability.SYS_ADMIN)
        .withCapAdd(Capability.NET_ADMIN).withBinds(binds).withNetworkMode(config.networkName)

    val createdChannel: Channel<String> = Channel(config.nContainers)
    coroutineScope {
        async(Dispatchers.IO) {
            proxies.map {
                async(Dispatchers.IO) {
                    it.createContainers(
                        containersInfo[it.shortHost]!!,
                        config.imageTag,
                        hostConfig,
                        volumes,
                        config.latencyFile,
                        config.nContainers,
                        createdChannel
                    )
                }
            }.joinAll()
        }.invokeOnCompletion { createdChannel.close() }

        var completed = 0
        for (id in createdChannel) {
            completed++
            print("  $completed / ${config.nContainers} (${(completed.toFloat() / config.nContainers * 100).toInt()}%) containers created\r")
        }
        println("  $completed / ${config.nContainers} (${(completed.toFloat() / config.nContainers * 100).toInt()}%) containers created")
    }

    println("--- Containers created")

    return proxies
}

suspend fun run(hosts: List<String>, arguments: Map<String, String>) {
    val proxies = if (arguments.containsKey("setup")) {
        val setup = setup(hosts, arguments)
        println("--- Waiting 10 seconds for containers to start")
        sleep(10000)
        setup
    } else {
        println("--- Creating clients")
        hosts.map { DockerProxy(it) }
    }

    val runStart = System.currentTimeMillis()

    if (!arguments.containsKey("config_file")) throw IllegalArgumentException("Missing argument: config_file")
    println("--- Parsing configuration file ${arguments["config_file"]}")

    val config = Yaml.default.decodeFromStream<Config>(FileInputStream(arguments["config_file"]!!))
    val expConfig = config.exps

    val locationsMap = readLocationsMapFromFile(config.setup.nodeLocationsFile)

    println("--- Getting existing containers")
    val containers = proxies.flatMap { it.listContainers() }.sortedBy { it.inspect.name.split("-")[1].toInt() }
    println("Found ${containers.size} containers")

    expConfig.forEach { exp ->
        if (exp.skip) {
            println("Skipping ${exp.name}")
            return@forEach
        }

        println("--- Starting experiment ${exp.name}")
        val expStart = System.currentTimeMillis()

        when (exp.type) {
            "basic" -> runBasicExp(exp, containers, locationsMap)
            "dying" -> runDyingExp(exp, containers, locationsMap)
            else -> throw IllegalArgumentException("Unknown experiment type ${exp.type}")
        }

        val expEnd = System.currentTimeMillis()
        println("--- Experiment ${exp.name} completed in ${(expEnd - expStart) / 1000}s. Total so far ${(expEnd - runStart) / 1000}s")
    }
}

fun readLocationsMapFromFile(nodeLocationsFile: String): Map<Int, Pair<Double, Double>> {
    //Open nodeLocationsFile and read line by line
    val locationsMap = mutableMapOf<Int, Pair<Double, Double>>()
    val lines = File(nodeLocationsFile).readLines()
    lines.forEach {
        val split = it.split("\\s+".toRegex())
        val id = split[0].toInt()
        val lat = split[1].toDouble()
        val lon = split[2].toDouble()
        locationsMap[id] = Pair(lat, lon)
    }
    return locationsMap
}

suspend fun runBasicExp(
    exp: Exp,
    containers: List<DockerProxy.ContainerProxy>,
    locationsMap: Map<Int, Pair<Double, Double>>
) {
    val neededContainers = exp.nodes.values.sum()
    if (containers.size < neededContainers)
        throw IllegalStateException("Not enough containers to run experiment, found ${containers.size} but need $neededContainers")

    val runningContainers = mutableListOf<DockerProxy.ContainerProxy>()
    startAllProcesses(exp, containers, runningContainers, null, locationsMap)

    sleep(exp.duration!! * 1000L)

    stopEverything(runningContainers)

    println("Changing ownership")
    containers[0].proxy.executeCommand(containers[0].inspect.id, arrayOf("chown", "-R", "$uid:$gid", "/logs/${exp.name}/"))

}

suspend fun runDyingExp(
    exp: Exp,
    containers: List<DockerProxy.ContainerProxy>,
    locationsMap: Map<Int, Pair<Double, Double>>
) {
    val neededContainers = exp.nodes.values.sum()
    if (containers.size < neededContainers)
        throw IllegalStateException("Not enough containers to run experiment, found ${containers.size} but need $neededContainers")

    val runningContainers = mutableListOf<DockerProxy.ContainerProxy>()
    val runningContainersPerRegion = mutableMapOf<String, MutableList<DockerProxy.ContainerProxy>>()
    startAllProcesses(exp, containers, runningContainers, runningContainersPerRegion, locationsMap)

    for (step in exp.steps) {
        println("Next step: $step")
        sleep(step.delay * 1000L)
        if (step.kill != null) {
            coroutineScope {
                println("Killing nodes")
                for (region in step.kill) {
                    for (node in region.value) {
                        val container = runningContainersPerRegion[region.key]!![node]
                        runningContainers.remove(container)
                        launch(Dispatchers.IO) {
                            container.proxy.executeCommand(container.inspect.id, arrayOf("killall", "java"))
                        }
                    }
                }
            }
        }
    }

    stopEverything(runningContainers)
}

private suspend fun startAllProcesses(
    exp: Exp,
    containers: List<DockerProxy.ContainerProxy>,
    runningContainers: MutableList<DockerProxy.ContainerProxy>,
    runningContainersPerRegion: MutableMap<String, MutableList<DockerProxy.ContainerProxy>>?,
    locationsMap: Map<Int, Pair<Double, Double>>,
) {
    print("Starting processes... ")
    coroutineScope {
        var index = 0
        for ((region, nodes) in exp.nodes) {
            if (runningContainersPerRegion != null) runningContainersPerRegion[region] = mutableListOf()
            val regionalDc = containers[index].inspect.config.hostName!!
            repeat(nodes) {
                val container = containers[index]
                val hostname = container.inspect.config.hostName!!
                val location = locationsMap[hostname.split("-")[1].toInt()]!!
                val cmd = mutableListOf(
                    "./start.sh",
                    "/logs/${exp.name}/$hostname.log",
                    "hostname=$hostname",
                    "region=$region",
                    "datacenter=$regionalDc",
                    "location_x=${location.first}",
                    "location_y=${location.second}"
                )
                if (exp.staticTree != null) {
                    cmd.add("tree_builder=Static")
                    cmd.add("tree_location=${exp.staticTree}")
                }
                launch(Dispatchers.IO) {
                    container.proxy.executeCommand(container.inspect.id, cmd.toTypedArray())
                }
                if (runningContainersPerRegion != null) runningContainersPerRegion[region]!!.add(container)
                runningContainers.add(container)
                index++
            }
        }
    }
    println("done.")
}

private suspend fun stopEverything(containers: List<DockerProxy.ContainerProxy>) {
    print("Stopping processes... ")
    coroutineScope {
        containers.forEach {
            launch(Dispatchers.IO) {
                it.proxy.executeCommand(it.inspect.id, arrayOf("killall", "java"))
            }
        }
    }
    print("waiting for processes to stop... ")
    coroutineScope {
        containers.distinctBy { it.proxy }.forEach {
            launch(Dispatchers.IO) {
                it.proxy.waitAllRunningCmds()
            }
        }
    }
    println("done.")

}

suspend fun interrupt(hosts: List<String>) {
    println("--- Creating clients")
    val proxies = hosts.map { DockerProxy(it) }
    println("--- Getting existing containers")
    val containers = proxies.flatMap { it.listContainers() }.sortedBy { it.inspect.name.split("-")[1].toInt() }
    println("Found ${containers.size} containers")
    println("--- Stopping processes")
    coroutineScope {
        containers.forEach {
            launch(Dispatchers.IO) { it.proxy.executeCommand(it.inspect.id, arrayOf("killall", "java")) }
        }
    }

}

suspend fun purge(hosts: List<String>, dockerClients: List<DockerProxy> = emptyList()) {

    println("--- Purging everything")

    val clients = dockerClients.ifEmpty { hosts.map { DockerProxy(it) } }

    coroutineScope {
        clients.map {
            async(Dispatchers.IO) {
                it.removeAllContainers()
                it.leaveSwarm()
            }
        }.joinAll()
    }
}

fun parseArguments(args: Array<String>): Map<String, String> {
    val params = mutableMapOf<String, String>()

    var i = 1 // start from index 1 to ignore the first element
    while (i < args.size) {
        val arg = args[i]

        if (arg.startsWith("--")) {
            val key = arg.substring(2)
            val value = if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                i++
                args[i]
            } else {
                ""
            }
            params[key] = value
        } else {
            throw IllegalArgumentException("Unexpected argument: $arg")
        }
        i++
    }
    return params
}

private fun sleep(durationMs: Long) {
    val startMs = System.currentTimeMillis()
    while (System.currentTimeMillis() - startMs < durationMs) {
        val elapsedMs = System.currentTimeMillis() - startMs
        Thread.sleep(200L.coerceAtMost(durationMs - elapsedMs))
        print("                                             \r")
        print("Sleeping for ${durationMs / 1000}: ${(durationMs - elapsedMs) / 1000}\r")
    }
    print("                                             \r")
    println("Slept for ${durationMs / 1000} seconds")
}