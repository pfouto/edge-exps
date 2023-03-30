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

fun main(args: Array<String>) = runBlocking {


    val command = args[0]
    val arguments = parseArguments(args)
    println(command)
    println(arguments)

    val oarNodeFile = System.getenv("OAR_NODE_FILE")
    val hosts = mutableListOf<String>()
    File(oarNodeFile).forEachLine { if (!hosts.contains(it)) hosts.add(it) }
    val me = InetAddress.getLocalHost().hostName
    if (hosts.contains(me))
        hosts.remove(me)
    else
        throw IllegalStateException("Hostname $me not found in OAR_NODE_FILE")

    println("I am $me")
    println("Hosts are: $hosts")

    val currentDir = Paths.get(System.getProperty("user.dir"))
    val uid = Files.getAttribute(currentDir, "unix:uid") as Int
    val gid = Files.getAttribute(currentDir, "unix:gid") as Int
    //println("$uid $gid")

    when (command) {
        "setup" -> setup(hosts, arguments)
        "run" -> run(hosts, arguments)
        "purge" -> purge(hosts)
        else -> throw IllegalArgumentException("Unknown command: $command")
    }

}

suspend fun setup(hosts: List<String>, arguments: Map<String, String>) {

    if (!arguments.containsKey("config_file")) throw IllegalArgumentException("Missing argument: config_file")
    println("Parsing configuration file ${arguments["config_file"]}")

    val config = Yaml.default.decodeFromStream<Config>(FileInputStream(arguments["config_file"]!!)).setup

    if (hosts.size * config.maxContainersPerMachine < config.nContainers)
        throw IllegalStateException(
            "Not enough hosts to run ${config.nContainers} containers, with a maximum of " +
                    "${config.maxContainersPerMachine} containers per node"
        )


    val nLinesTc = Files.lines(Paths.get(config.tcFolder, config.latencyFile)).count()
    if (nLinesTc < config.nContainers)
        throw IllegalStateException("Not enough lines in ${config.latencyFile} to run ${config.nContainers} nodes")

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
        } else
            throw e
    }

    println("--- Creating clients")
    val proxies = hosts.map { DockerProxy(it) }

    purge(hosts, proxies)

    println("--- Setting up swarm and network")
    val tokens = proxies[0].initSwarm()
    proxies.drop(1).forEach { it.joinSwarm(tokens, proxies[0].shortHost) }
    if (proxies[0].getSwarmMembers().size != proxies.size)
        throw IllegalStateException("Swarm members are not equal to the number of hosts")

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
    val hostConfig = HostConfig()
        .withAutoRemove(true)
        .withPrivileged(true)
        .withCapAdd(Capability.SYS_ADMIN)
        .withCapAdd(Capability.NET_ADMIN)
        .withBinds(binds)
        .withNetworkMode(config.networkName)

    val createdChannel: Channel<String> = Channel(config.nContainers)
    coroutineScope {
        async(Dispatchers.IO) {
            proxies.map {
                async(Dispatchers.IO) {
                    it.createContainers(
                        containersInfo[it.shortHost]!!, config.imageTag, hostConfig,
                        volumes, config.latencyFile, config.nContainers, createdChannel
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


}

fun run(hosts: List<String>, arguments: Map<String, String>) {

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
                args[i + 1]
            } else {
                throw IllegalArgumentException("Missing value for argument: $arg")
            }
            params[key] = value
            i++
        } else {
            throw IllegalArgumentException("Unexpected argument: $arg")
        }
        i++
    }
    return params
}