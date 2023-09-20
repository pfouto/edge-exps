import cassandra_latency.runCassandraLatency
import cassandra_micro.runCassandraMicro
import cassandra_periodic.runCassandraPeriodic
import com.charleskorn.kaml.*
import engage_micro.runMicroEngage
import fail.runFail
import kotlinx.coroutines.*
import latency.runLatency
import micro.runMicro
import mobility.runMobility
import mobilityAdv.runMobilityAdv
import periodicAdv.runPeriodicAdv
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.hc.client5.http.HttpHostConnectException
import periodic.runPeriodic
import utils.DockerConfig
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress

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

    when (command) {
        "setup" -> setup(me, hosts, arguments)
        "run" -> run(me, hosts, arguments)
        "purge" -> purge(me, hosts)
        "interrupt" -> interrupt(me, hosts)
        else -> throw IllegalArgumentException("Unknown command: $command")
    }

}

suspend fun setup(me: String, hosts: List<String>, arguments: Map<String, String>): Pair<DockerConfig, Proxies> {

    if (!arguments.containsKey("config")) throw IllegalArgumentException("Missing argument: config")
    println("Parsing configuration file ${arguments["config"]}")

    val expSetup = Yaml.default.parseToYamlNode(FileInputStream(arguments["config"]!!))
    val dockerConfigLocation = expSetup.yamlMap.get<YamlScalar>("dockerConfig")!!.content
    val dockerConfig = Yaml.default.decodeFromStream<DockerConfig>(FileInputStream("configs/$dockerConfigLocation"))


    val requiredNodeMachines =
        dockerConfig.nNodes / dockerConfig.maxNodesPerMachine + if (dockerConfig.nNodes % dockerConfig.maxNodesPerMachine > 0) 1 else 0
    val requiredClientMachines =
        dockerConfig.nClients / dockerConfig.maxClientsPerMachine + if (dockerConfig.nClients % dockerConfig.maxClientsPerMachine > 0) 1 else 0

    if (hosts.size < requiredNodeMachines + requiredClientMachines)
        throw IllegalStateException(
            "Not enough machines to run ${dockerConfig.nNodes} nodes and ${dockerConfig.nClients} clients on " +
                    "${hosts.size} machines with ${dockerConfig.maxNodesPerMachine} nodes and ${dockerConfig.maxClientsPerMachine} " +
                    "clients per machine"
        )

    val nodeMachines = hosts.subList(0, requiredNodeMachines)
    val clientMachines = hosts.subList(requiredNodeMachines, requiredNodeMachines + requiredClientMachines)

    println("--- Node machines: ${listOf(me)} + $nodeMachines")
    println("--- Client machines: $clientMachines")

    println("--- Checking docker status...")
    try {
        val client = DockerProxy(me)
        println("Docker seems to be usable")
        client.close()
    } catch (e: Exception) {
        if (ExceptionUtils.getRootCause(e) is HttpHostConnectException) {
            //println("--- Docker is not running on ${me}, will try to install on all hosts")
            //Thread.sleep(3000)
            //DockerProxy.gridInstallDockerParallel(listOf(me) + hosts)
            println("--- Docker not running on $me")
            throw e
        } else throw e
    }

    println("--- Creating clients")

    val proxies = Proxies(DockerProxy(me), nodeMachines.map { DockerProxy(it) }, clientMachines.map { DockerProxy(it) })

    if (proxies.dcProxy.amSwarmManager() && proxies.dcProxy.getSwarmMembers().size == proxies.allProxies.size) {
        println("Swarm already exists, will not create it")
        removeAllContainers(proxies)
        proxies.allProxies.forEach { it.deleteVolume("logs")}
    } else {
        purge(me, hosts)
        println("--- Restarting docker service")
        DockerProxy.restartDockerService(listOf(me) + hosts)
        println("--- Reconnecting proxies")
        proxies.allProxies.forEach { it.reconnect() }
        println("--- Setting up swarm")
        val tokens = proxies.dcProxy.initSwarm()
        proxies.nodeProxies.forEach { it.joinSwarm(tokens, proxies.dcProxy.shortHost) }
        proxies.clientProxies.forEach { it.joinSwarm(tokens, proxies.dcProxy.shortHost) }

        if (proxies.dcProxy.getSwarmMembers().size != proxies.allProxies.size)
            throw IllegalStateException("Swarm members are not equal to the number of hosts")
    }

    val networks = proxies.dcProxy.listNetworks()
    if (networks.contains(dockerConfig.networkName)) {
        println("--- Network ${dockerConfig.networkName} already exists, will not create it")
    } else {
        println("--- Creating overlay network ${dockerConfig.networkName}")
        proxies.dcProxy.createOverlayNetwork(dockerConfig.networkName, dockerConfig.subnet, dockerConfig.gateway)
    }

    println("--- Loading images")
    coroutineScope {
        proxies.allProxies.map {
            async(Dispatchers.IO) {
                it.loadImage(dockerConfig.imageLoc)
            }
        }.joinAll()
    }

    return Pair(dockerConfig, proxies)
}

suspend fun run(me: String, hosts: List<String>, arguments: Map<String, String>) {

    val (dockerConfig, proxies) = setup(me, hosts, arguments)

    if (!arguments.containsKey("config")) throw IllegalArgumentException("Missing argument: config")
    println("--- Parsing configuration file ${arguments["config"]}")

    val expSetup = Yaml.default.parseToYamlNode(FileInputStream(arguments["config"]!!))
    val expNodes = expSetup.yamlMap.get<YamlList>("exps")!!

    println(" ------------------ STARTING EXPERIENCES ------------------")

    expNodes.items.forEach {
        when(val expType = it.yamlMap.get<YamlScalar>("type")!!.content) {
            "micro" -> runMicro(it, proxies, dockerConfig)
            "micro_engage" -> runMicroEngage(it, proxies, dockerConfig)
            "fails" -> runFail(it, proxies, dockerConfig)
            "periodic" -> runPeriodic(it, proxies, dockerConfig)
            "periodicAdv" -> runPeriodicAdv(it, proxies, dockerConfig)
            "mobility" -> runMobility(it, proxies, dockerConfig)
            "mobilityAdv" -> runMobilityAdv(it, proxies, dockerConfig)
            "latency" -> runLatency(it, proxies, dockerConfig)
            "micro_cassandra" -> runCassandraMicro(it, proxies, dockerConfig)
            "latency_cassandra" -> runCassandraLatency(it, proxies, dockerConfig)
            "periodic_cassandra" -> runCassandraPeriodic(it, proxies, dockerConfig)

            else -> throw IllegalArgumentException("Unknown experiment type: $expType")
        }
    }
    println("------------------ EXPERIENCES FINISHED ------------------")
}

data class Location(val x: Double, val y: Double, val slice: Int)

fun readLocationsMapFromFile(nodeLocationsFile: String): Map<Int, Location> {
    //Open nodeLocationsFile and read line by line
    val locationsMap = mutableMapOf<Int, Location>()
    val lines = File(nodeLocationsFile).readLines()
    lines.forEach {
        val split = it.split("\\s+".toRegex())
        val id = split[0].toInt()
        val x = split[1].toDouble()
        val y = split[2].toDouble()
        val slice = split[3].toInt()
        locationsMap[id] = Location(x, y, slice)
    }
    return locationsMap
}

suspend fun stopEverything(containers: List<DockerProxy.ContainerProxy>) {
    //print("Stopping processes... ")
    coroutineScope {
        containers.forEach {
            launch(Dispatchers.IO) {
                it.proxy.executeCommand(it.inspect.id, arrayOf("killall", "java"))
            }
        }
    }
    //print("waiting for processes to stop... ")
    coroutineScope {
        containers.distinctBy { it.proxy }.forEach {
            launch(Dispatchers.IO) {
                it.proxy.waitAllRunningCmds()
            }
        }
    }
    //println("done.")

}

suspend fun interrupt(me: String, hosts: List<String>) {
    println("--- Creating clients")
    val proxies = (listOf(me) + hosts).map { DockerProxy(it) }
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

suspend fun purge(me: String, hosts: List<String>) {

    println("--- Purging everything")

    val clients = (listOf(me) + hosts).map { DockerProxy(it) }

    println(clients.map { it.shortHost })

    coroutineScope {
        clients.map {
            async(Dispatchers.IO) {
                it.removeAllContainers()
                it.deleteVolume("logs")
                it.leaveSwarm()
            }
        }.joinAll()
    }
}

suspend fun removeAllContainers(proxies: Proxies) {

    println("--- Removing all containers")

    coroutineScope {
        proxies.allProxies.map {
            async(Dispatchers.IO) {
                it.removeAllContainers()
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

fun sleep(durationMs: Long) {
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

data class Proxies(
    val dcProxy: DockerProxy, val nodeProxies: List<DockerProxy>, val clientProxies: List<DockerProxy>,
    val allProxies: List<DockerProxy> = listOf(dcProxy) + nodeProxies + clientProxies,
)