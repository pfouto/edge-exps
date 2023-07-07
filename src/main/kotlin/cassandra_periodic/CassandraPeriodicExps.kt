package cassandra_periodic

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
import org.apache.commons.io.FileUtils
import readLocationsMapFromFile
import removeAllContainers
import sleep
import stopEverything
import utils.DockerConfig
import utils.TcConfig
import java.io.File
import java.io.FileInputStream
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.system.exitProcess

suspend fun runCassandraPeriodic(expYaml: YamlNode, proxies: Proxies, dockerConfig: DockerConfig) {
    val expConfig = Yaml.default.decodeFromString<CassandraPeriodicConfig>(expYaml.contentToString())
    val nExps = expConfig.tcSetup.size * expConfig.nodes.size *
            expConfig.readPercents.size * expConfig.periodicModes.size
    println("------ Starting exp ${expConfig.name} with $nExps experiments ------")
    var nExp = 0
    expConfig.tcSetup.forEach { tcConfigFile ->

        val tcConfig = Yaml.default.decodeFromStream<TcConfig>(FileInputStream("configs/$tcConfigFile"))
        launchCassandraContainers(tcConfig, proxies, dockerConfig)
        val allNodes = (listOf(proxies.dcProxy) + proxies.nodeProxies).flatMap { it.listContainers() }
            .sortedBy { it.inspect.name.split("-")[1].toInt() }
        val clients =
            proxies.clientProxies.flatMap { it.listContainers() }.sortedBy { it.inspect.name.split("-")[1].toInt() }

        val locationsMap = readLocationsMapFromFile("tc/${tcConfig.nodesFile}")
        expConfig.nodes.forEach { nNodes ->
            val nodes = allNodes.take(nNodes)
            if (nodes.size < nNodes)
                throw Exception("Not enough nodes for experiment")

            expConfig.periodicModes.forEach { mode ->
                expConfig.readPercents.forEach read@{ readPercent ->
                    nExp++
                    val tcBaseFileNumber = tcConfigFile.split(".")[0].split("_")[1]
                    val logsPath =
                        "${expConfig.name}/${nNodes}n_${mode}_${readPercent}r_${tcBaseFileNumber}"

                    if (!File("${dockerConfig.logsFolder}/$logsPath").exists()) {
                        println(
                            "---------- Running experiment $nExp/$nExps with $tcConfigFile, $nNodes nodes, " +
                                    "$mode, $readPercent reads -------"
                        )
                    } else {
                        println(
                            "---------- Skipping existing $nExp/$nExps with $tcConfigFile, $nNodes nodes, " +
                                    "$mode, $readPercent reads -------"
                        )
                        return@read
                    }


                    runExp(
                        nodes, clients, locationsMap, expConfig, nNodes, mode,
                        readPercent, "/logs/$logsPath"
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
        removeAllContainers(proxies)
        proxies.allProxies.forEach { it.deleteVolume("logs") }
    }
}

private suspend fun runExp(
    nodes: List<DockerProxy.ContainerProxy>, clients: List<DockerProxy.ContainerProxy>,
    locationsMap: Map<Int, Location>, expConfig: CassandraPeriodicConfig, nNodes: Int, mode: String,
    readPercent: Int, logsPath: String,
) {

    startAllCassandras(nodes)
    println("Waiting for cassandra to stabilize")
    when (nNodes) {
        200 -> sleep(120000) // 2 minutes
        20 -> sleep(120000) //2 minutes
        1 -> sleep(80000) //1.5 minutes
        else -> throw Exception("Invalid number of nodes $nNodes")
    }

    println("Creating keyspaces and tables")

    val partitions = expConfig.partitions
    val dcsPerPartition = mutableMapOf<String, MutableList<String>>()
    partitions.values.forEach { p -> dcsPerPartition[p] = mutableListOf() }

    when (expConfig.dataDistribution) {
        "periodic" -> {
            nodes.forEach { n ->
                val nodeNumber = n.inspect.name.split("-")[1].toInt()
                if (nodeNumber == 0) {
                    dcsPerPartition.forEach { (_, list) -> list.add("dc0") }
                } else {
                    val slice = locationsMap[nodeNumber]!!.slice
                    dcsPerPartition[partitions[slice]]!!.add("dc${nodeNumber}")
                    dcsPerPartition[partitions[(slice + 1) % partitions.size]]!!.add("dc${nodeNumber}")
                    dcsPerPartition[partitions[if (slice - 1 < 0) partitions.size - 1 else slice - 1]]!!.add("dc${nodeNumber}")
                }
            }
        }

        else -> throw Exception("Invalid data distribution ${expConfig.dataDistribution}")
    }

    val mainNode = nodes[0]
    dcsPerPartition.forEach { (partition, dcs) ->
        //println("Creating keyspace $partition")
        var keyspaceCommand =
            "CREATE KEYSPACE IF NOT EXISTS $partition WITH REPLICATION = {'class': 'NetworkTopologyStrategy'"
        dcs.forEach { dc -> keyspaceCommand = keyspaceCommand.plus(", '${dc}': 1") }
        keyspaceCommand = keyspaceCommand.plus("};")
        val command1 = arrayOf("cqlsh", "node-0", "-e", keyspaceCommand)
        mainNode.proxy.executeCommandSync(mainNode.inspect.id, command1)
        val command2 = arrayOf(
            "cqlsh", "node-0", "-e",
            "CREATE TABLE IF NOT EXISTS ${partition}.usertable (y_id varchar primary key, field0 varchar);"
        )
        mainNode.proxy.executeCommandSync(mainNode.inspect.id, command2)
    }

    sleep(10000)
    //println("Starting clients")
    startAllClients(clients, locationsMap, nNodes, mode, readPercent, logsPath, expConfig)

    //println("Waiting for experiment to finish")
    sleep(expConfig.duration * 1000L)

    print("Stopping clients... ")
    stopEverything(clients)
    print("Stopping nodes... ")
    stopEverything(nodes)
    print("Cleaning data... ")
    coroutineScope {
        nodes.forEach {
            launch(Dispatchers.IO) {
                it.proxy.executeCommand(it.inspect.id, arrayOf("rm", "-rf", "/var/lib/cassandra/commitlog"))
                it.proxy.executeCommand(it.inspect.id, arrayOf("rm", "-rf", "/var/lib/cassandra/data"))
                it.proxy.executeCommand(it.inspect.id, arrayOf("rm", "-rf", "/var/lib/cassandra/hints"))
                it.proxy.executeCommand(it.inspect.id, arrayOf("rm", "-rf", "/var/lib/cassandra/saved_caches"))
            }
        }
    }
    println("Done")
}

private suspend fun startAllClients(
    clients: List<DockerProxy.ContainerProxy>, locationsMap: Map<Int, Location>,
    nNodes: Int, mode: String, readPercent: Int, logsPath: String,
    expConfig: CassandraPeriodicConfig,
) {
    val random = java.util.Random()
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
                "-p", "db=CassandraCQLClient",
                "-p", "cassandra.readtimeoutmillis=30000",
                "-p", "hosts=$clientNode",
                "-p", "readproportion=${readPercent / 100.0}",
                "-p", "updateproportion=${(100 - readPercent) / 100.0}",
                "-p", "status.interval=1",
                "-p", "cassandra.writeconsistencylevel=ONE"
            )

            when (expConfig.dataDistribution) {
                "periodic" -> {
                    val localTables = mutableListOf<String>()
                    val remoteTables = mutableListOf<String>()
                    if (nodeSlice != -1) {
                        localTables.add(partitions[nodeSlice]!!)
                        localTables.add(partitions[(nodeSlice + 1) % partitions.size]!!)
                        localTables.add(partitions[if (nodeSlice - 1 < 0) partitions.size - 1 else nodeSlice - 1]!!)
                        remoteTables.addAll(partitions.values)
                        remoteTables.removeAll(localTables)
                    } else {
                        localTables.addAll(partitions.values)
                        remoteTables.addAll(partitions.values)
                    }
                    cmd.add("-p")
                    cmd.add("workload=site.ycsb.workloads.EdgePeriodicWorkload")
                    cmd.add("-p")
                    cmd.add("local_tables=${localTables.joinToString(",")}")
                    cmd.add("-p")
                    cmd.add("remote_tables=${remoteTables.joinToString(",")}")
                    cmd.add("-p")
                    cmd.add("remote_duration=${expConfig.periodicRemoteDuration}")
                    cmd.add("-p")
                    cmd.add("remote_interval=${expConfig.periodicRemoteInterval}")
                    cmd.add("-p")
                    cmd.add("recordcount=${expConfig.recordCount}")
                    when (mode) {
                        "coordinated" -> {
                            cmd.add("-p")
                            cmd.add("remote_offset=${expConfig.periodicRemoteInterval}")
                        }

                        "uncoordinated" -> {
                            val offset =
                                expConfig.periodicRemoteInterval + random.nextInt(expConfig.periodicRemoteInterval.toInt())
                            cmd.add("-p")
                            cmd.add("remote_offset=${offset}")
                        }
                    }
                }

                else -> throw Exception("Invalid data distribution $expConfig.dataDistribution")
            }
            launch(Dispatchers.IO) {
                container.proxy.executeCommand(container.inspect.id, cmd.toTypedArray(), "/client")
            }
        }
    }
}

private fun closestActiveNode(clientNumber: Int, locationsMap: Map<Int, Location>, nNodes: Int): Pair<Int, Location> {
    if (nNodes == 1) return Pair(0, locationsMap[0]!!)

    val clientLoc = locationsMap[clientNumber]!!
    val activeNodes = locationsMap.filter { it.key < nNodes && it.key != 0 }
    val closestNode = activeNodes.minByOrNull { distance(clientLoc, it.value) }!!
    return Pair(closestNode.key, closestNode.value)
}

private fun distance(loc1: Location, loc2: Location): Double {
    return sqrt((loc1.x - loc2.x).pow(2.0) + (loc1.y - loc2.y).pow(2.0))
}

private suspend fun startAllCassandras(
    nodes: List<DockerProxy.ContainerProxy>,
) {

    val seeds = nodes.joinToString(",") { it.inspect.config.hostName!! }
    //print("Starting nodes... ")

    coroutineScope {
        nodes.forEach { container ->
            val hostname = container.inspect.config.hostName!!
            val nodeNumber = hostname.split("-")[1].toInt()

            val env = listOf(
                "CASSANDRA_CLUSTER_NAME=edgecluster",
                "CASSANDRA_DC=dc$nodeNumber",
                "CASSANDRA_ENDPOINT_SNITCH=GossipingPropertyFileSnitch",
                "CASSANDRA_SEEDS=$seeds",
                "HEAP_NEWSIZE=256M",
                "MAX_HEAP_SIZE=2048M",
                "CASSANDRA_BROADCAST_ADDRESS=${container.inspect.config.hostName}"
            )
            val cmd = mutableListOf(
                "docker-entrypoint.sh", "cassandra", "-f"
            )

            launch(Dispatchers.IO) {
                container.proxy.executeCommand(container.inspect.id, cmd.toTypedArray(), env = env)
            }
        }
    }
}


private suspend fun launchCassandraContainers(tcConfig: TcConfig, proxies: Proxies, dockerConfig: DockerConfig) {
    println("--- Creating containers for ${tcConfig.latencyFile}...")

    val nodeContainersInfo = mutableMapOf<String, MutableList<Pair<Int, String>>>()
    proxies.nodeProxies.forEach {
        DockerProxy.runCommand(it.shortHost, "mount /mnt/ramdisk")
        DockerProxy.runCommand(it.shortHost, "rm -rf /mnt/ramdisk/cassandra")
        DockerProxy.runCommand(it.shortHost, "mkdir /mnt/ramdisk/cassandra")
        nodeContainersInfo[it.shortHost] = mutableListOf()
    }
    nodeContainersInfo[proxies.dcProxy.shortHost] = mutableListOf()
    DockerProxy.runCommand(proxies.dcProxy.shortHost, "mount /mnt/ramdisk")
    DockerProxy.runCommand(proxies.dcProxy.shortHost, "rm -rf /mnt/ramdisk/cassandra")
    DockerProxy.runCommand(proxies.dcProxy.shortHost, "mkdir /mnt/ramdisk/cassandra")

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
    val cassVol = Volume("/var/lib/cassandra")

    val volumes = Volumes(modulesVol, logsVol, tcVol, serverVol, clientVol, cassVol)

    val binds = Binds(
        Bind("/lib/modules", modulesVol),
        Bind("logs", logsVol),
        Bind(dockerConfig.tcFolder, tcVol, AccessMode.ro),
        Bind(dockerConfig.serverFolder, serverVol, AccessMode.ro),
        Bind(dockerConfig.clientFolder, clientVol, AccessMode.ro)
    )

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
                    nodeContainersInfo[it.shortHost]!!.forEach { p ->
                        DockerProxy.runCommand(it.shortHost, "mkdir /mnt/ramdisk/cassandra/node-${p.first}")
                    }
                    it.createServerContainersCassandra(
                        nodeContainersInfo[it.shortHost]!!,
                        dockerConfig.imageTag,
                        200000,
                        dockerConfig.networkName,
                        binds,
                        volumes,
                        tcConfig.latencyFile,
                        dockerConfig.nNodes,
                        createdChannel,
                        cassVol
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
