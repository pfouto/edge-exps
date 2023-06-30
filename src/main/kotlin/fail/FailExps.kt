package fail

import DockerProxy
import Location
import Proxies
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.decodeFromStream
import com.github.dockerjava.api.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.decodeFromString
import micro.MicroConfig
import org.apache.commons.io.FileUtils
import readLocationsMapFromFile
import removeAllContainers
import sleep
import stopEverything
import utils.DockerConfig
import utils.TcConfig
import java.io.File
import java.io.FileInputStream
import kotlin.math.pow
import kotlin.math.sqrt

suspend fun runFail(expYaml: YamlNode, proxies: Proxies, dockerConfig: DockerConfig) {
    val expConfig = Yaml.default.decodeFromString<FailConfig>(expYaml.contentToString())
    val nExps = expConfig.tcSetup.size * expConfig.nodes.size * expConfig.dataDistribution.size *
            expConfig.readPercents.size * expConfig.failPercents.size * expConfig.clientPersistence.size
    println("------ Starting exp ${expConfig.name} with $nExps experiments ------")
    var nExp = 0
    expConfig.tcSetup.forEach { tcConfigFile ->

        val tcConfig = Yaml.default.decodeFromStream<TcConfig>(FileInputStream("configs/$tcConfigFile"))
        launchContainers(tcConfig, proxies, dockerConfig)
        val allNodes = (listOf(proxies.dcProxy) + proxies.nodeProxies).flatMap { it.listContainers() }
            .sortedBy { it.inspect.name.split("-")[1].toInt() }
        val clients =
            proxies.clientProxies.flatMap { it.listContainers() }.sortedBy { it.inspect.name.split("-")[1].toInt() }

        val locationsMap = readLocationsMapFromFile("tc/${tcConfig.nodesFile}")
        expConfig.nodes.forEach { nNodes ->
            val nodes = allNodes.take(nNodes)
            if (nodes.size < nNodes)
                throw Exception("Not enough nodes for experiment")

            expConfig.dataDistribution.forEach { dataDistribution ->
                expConfig.readPercents.forEach { readPercent ->
                    expConfig.clientPersistence.forEach { persistence ->
                        expConfig.failPercents.forEach thread@{ failPercent ->
                            nExp++
                            val tcBaseFileNumber = tcConfigFile.split(".")[0].split("_")[1]
                            val logsPath =
                                "${expConfig.name}/${nNodes}n_${dataDistribution}_${readPercent}r_${persistence}p_${failPercent}f_${tcBaseFileNumber}"

                            if (!File("${dockerConfig.logsFolder}/$logsPath").exists()) {
                                println(
                                    "---------- Running experiment $nExp/$nExps with $tcConfigFile, $nNodes nodes, " +
                                            "$dataDistribution, $readPercent reads, $persistence persistence," +
                                            " $failPercent fail percent -------"
                                )
                            } else {
                                println(
                                    "---------- Skipping experiment $nExp/$nExps with $tcConfigFile, $nNodes nodes, " +
                                            "$dataDistribution, $readPercent reads, $persistence persistence," +
                                            " $failPercent fail percent -------"
                                )
                                return@thread
                            }

                            runExp(
                                nodes, clients, locationsMap, expConfig, tcConfigFile, nNodes,
                                dataDistribution, readPercent, persistence, failPercent, "/logs/$logsPath"
                            )
                            FileUtils.deleteDirectory(File("${dockerConfig.logsFolder}/$logsPath"))
                            println("Getting logs")
                            coroutineScope {
                                (allNodes + clients).map { it.proxy }.distinct().forEach { p ->
                                    launch(Dispatchers.IO) {
                                        p.cp(
                                            "/logs/${logsPath}",
                                            "${dockerConfig.logsFolder}/${expConfig.name}"
                                        )
                                    }
                                }
                            }
                        }
                    }

                }
            }
        }
        removeAllContainers(proxies)
        proxies.allProxies.forEach { it.deleteVolume("logs") }
    }
}

private suspend fun runExp(
    nodes: List<DockerProxy.ContainerProxy>, clients: List<DockerProxy.ContainerProxy>,
    locationsMap: Map<Int, Location>, expConfig: FailConfig, tcConfigFile: String, nNodes: Int,
    dataDistribution: String, readPercent: Int, persistence: Int, failPercent: Int, logsPath: String,
) {
    startAllNodes(nodes, locationsMap, logsPath, expConfig.propagateTimeout)
    //println("Waiting for tree to stabilize")
    when (nNodes) {
        200 -> sleep(40000)
        20 -> sleep(20000)
        else -> throw Exception("Invalid number of nodes $nNodes")
    }

    //println("Starting clients")
    startAllClients(clients, locationsMap, dataDistribution, nNodes, readPercent, persistence, logsPath, expConfig)

    sleep(expConfig.failAt * 1000L)


    val nodesToKill = nodes.subList(1, nodes.size).shuffled().take((failPercent / 100.0 * nNodes).toInt())
    println("Killing ${nodesToKill.size} nodes")
    coroutineScope {
        nodesToKill.forEach {
            launch(Dispatchers.IO) {
                it.proxy.executeCommand(it.inspect.id, arrayOf("killall", "java"))
            }
        }
    }

    println("Waiting for experiment to finish")
    sleep((expConfig.duration - expConfig.failAt) * 1000L)

    print("Stopping clients... ")
    stopEverything(clients)
    print("Stopping nodes... ")
    stopEverything(nodes)
    println("Done")
}

private suspend fun startAllClients(
    clients: List<DockerProxy.ContainerProxy>, locationsMap: Map<Int, Location>,
    dataDistribution: String, nNodes: Int, readPercent: Int, persistence: Int, logsPath: String,
    expConfig: FailConfig,
) {
    coroutineScope {
        clients.forEach { container ->
            val hostname = container.inspect.config.hostName!!
            val clientNumber = hostname.split("-")[1].toInt()
            val closestNode = closestActiveNode(clientNumber, locationsMap, nNodes)
            val clientNode = "node-${closestNode.first}"
            val nodeSlice = closestNode.second.slice
            val partitions = expConfig.partitions
            val cmd = mutableListOf(
                "./start.sh",
                "$logsPath/$hostname",
                "-threads", "${expConfig.threads}",
                "-target", "${expConfig.limit}",
                "-p", "timeout_millis=30000",
                "-p", "migration_timeout_millis=10000",
                "-p", "db=EdgeMigratingClient",
                "-p", "host=$clientNode",
                "-p", "readproportion=${readPercent / 100.0}",
                "-p", "updateproportion=${(100 - readPercent) / 100.0}",
                "-p", "status.interval=1",
                "-p", "persistence=$persistence",
                )

            when (dataDistribution) {
                "global" -> {
                    cmd.add("-p")
                    cmd.add("workload=site.ycsb.workloads.EdgeFixedWorkload")
                    cmd.add("-p")
                    cmd.add("tables=${partitions.values.joinToString(",")}")
                }

                else -> throw Exception("Invalid data distribution $dataDistribution")
            }
            launch(Dispatchers.IO) {
                container.proxy.executeCommand(container.inspect.id, cmd.toTypedArray(), "/client")
            }
        }
    }
}

fun closestActiveNode(clientNumber: Int, locationsMap: Map<Int, Location>, nNodes: Int): Pair<Int, Location> {
    if(nNodes == 1) return Pair(0, locationsMap[0]!!)

    val clientLoc = locationsMap[clientNumber]!!
    val activeNodes = locationsMap.filter { it.key < nNodes && it.key != 0 }
    val closestNode = activeNodes.minByOrNull { distance(clientLoc, it.value) }!!
    return Pair(closestNode.key, closestNode.value)
}

private fun distance(loc1: Location, loc2: Location): Double {
    return sqrt((loc1.x - loc2.x).pow(2.0) + (loc1.y - loc2.y).pow(2.0))
}

private suspend fun startAllNodes(
    nodes: List<DockerProxy.ContainerProxy>,
    locationsMap: Map<Int, Location>,
    logsPath: String, propagateTimeout: Int,
) {
    //print("Starting nodes... ")
    coroutineScope {
        val dc = nodes[0].inspect.config.hostName!!
        nodes.forEach { container ->
            val hostname = container.inspect.config.hostName!!
            val nodeNumber = hostname.split("-")[1].toInt()
            val location = locationsMap[nodeNumber]!!
            val cmd = mutableListOf(
                "./start.sh", "$logsPath/$hostname", "hostname=$hostname", "region=eu", "datacenter=$dc",
                "location_x=${location.x}", "location_y=${location.y}", "tree_builder_nnodes=${nodes.size}",
                "propagate_timeout=$propagateTimeout",
            )

            launch(Dispatchers.IO) {
                container.proxy.executeCommand(container.inspect.id, cmd.toTypedArray(), "/server")
            }
        }
    }
    //println("done.")
}


private suspend fun launchContainers(tcConfig: TcConfig, proxies: Proxies, dockerConfig: DockerConfig) {
    println("--- Creating containers for ${tcConfig.latencyFile}...")

    val nodeContainersInfo = mutableMapOf<String, MutableList<Pair<Int, String>>>()
    proxies.nodeProxies.forEach { nodeContainersInfo[it.shortHost] = mutableListOf() }
    nodeContainersInfo[proxies.dcProxy.shortHost] = mutableListOf()

    val nodeIps = File("tc/serverIps.txt").readLines()

    for (containerNumber in dockerConfig.nNodes - 1 downTo 1) {
        val proxy = proxies.nodeProxies[containerNumber % proxies.nodeProxies.size]
        nodeContainersInfo[proxy.shortHost]!!.add(Pair(containerNumber, nodeIps[containerNumber]))
    }
    nodeContainersInfo[proxies.dcProxy.shortHost]!!.add(Pair(0, nodeIps[0]))

    val clientContainersInfo = mutableMapOf<String, MutableList<Pair<Int, String>>>()
    proxies.clientProxies.forEach { clientContainersInfo[it.shortHost] = mutableListOf() }
    val clientIps = File("tc/clientIps.txt").readLines()
    for (containerNumber in dockerConfig.nClients downTo 1) {
        val proxy = proxies.clientProxies[containerNumber % proxies.clientProxies.size]
        clientContainersInfo[proxy.shortHost]!!.add(Pair(containerNumber, clientIps[containerNumber]))
    }

    val modulesVol = Volume("/lib/modules")
    val logsVol = Volume("/logs")
    val tcVol = Volume("/tc")
    val serverVol = Volume("/server")
    val clientVol = Volume("/client")

    val volumes = Volumes(modulesVol, logsVol, tcVol, serverVol, clientVol)

    val binds = Binds(
        Bind("/lib/modules", modulesVol),
        Bind("logs", logsVol),
        Bind(dockerConfig.tcFolder, tcVol, AccessMode.ro),
        Bind(dockerConfig.serverFolder, serverVol, AccessMode.ro),
        Bind(dockerConfig.clientFolder, clientVol, AccessMode.ro)
    )

    val hostConfigFull = HostConfig().withAutoRemove(true).withPrivileged(true).withCapAdd(Capability.SYS_ADMIN)
        .withCapAdd(Capability.NET_ADMIN).withBinds(binds).withNetworkMode(dockerConfig.networkName)
    val hostConfigLimited = HostConfig().withAutoRemove(true).withPrivileged(true).withCapAdd(Capability.SYS_ADMIN)
        .withCapAdd(Capability.NET_ADMIN).withBinds(binds).withNetworkMode(dockerConfig.networkName)
        .withCpuQuota(200000)

    val totalContainers = dockerConfig.nNodes + dockerConfig.nClients
    val createdChannel: Channel<String> = Channel(totalContainers)
    coroutineScope {
        async(Dispatchers.IO) {

            //println(nodeContainersInfo.map { it.key to it.value.size })
            val nodeCallbacks = (listOf(proxies.dcProxy) + proxies.nodeProxies).map {
                async(Dispatchers.IO) {
                    it.createServerContainers(
                        nodeContainersInfo[it.shortHost]!!,
                        dockerConfig.imageTag,
                        hostConfigFull,
                        hostConfigLimited,
                        volumes,
                        tcConfig.latencyFile,
                        dockerConfig.nNodes,
                        createdChannel
                    )
                }
            }
            //println(clientContainersInfo.map { it.key to it.value.size })
            val clientCallbacks = proxies.clientProxies.map {
                async(Dispatchers.IO) {
                    it.createClientContainers(
                        clientContainersInfo[it.shortHost]!!, dockerConfig.imageTag,
                        hostConfigLimited, volumes, tcConfig.latencyFile, dockerConfig.nNodes, createdChannel
                    )
                }
            }
            (clientCallbacks + nodeCallbacks).joinAll()

        }.invokeOnCompletion { createdChannel.close() }

        var completed = 0
        for (id in createdChannel) {
            completed++
            print("  $completed / $totalContainers (${(completed.toFloat() / totalContainers * 100).toInt()}%) containers created\r")
        }
        println("  $completed / $totalContainers (${(completed.toFloat() / totalContainers * 100).toInt()}%) containers created")
    }
}
