import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.decodeFromStream
import com.github.dockerjava.api.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.decodeFromString
import utils.DockerConfig
import utils.TcConfig
import java.io.File
import java.io.FileInputStream

suspend fun runMicro(expYaml: YamlNode, proxies: Proxies, dockerConfig: DockerConfig) {
    val expConfig = Yaml.default.decodeFromString<MicroConfig>(expYaml.contentToString())
    println("------ Starting exp ${expConfig.name}")

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
                val logsPath = "/logs/${expConfig.name}/$tcConfigFile/$nNodes/$dataDistribution"

                println(
                    "--- Running experiment with $tcConfigFile, $nNodes nodes, " +
                            "$dataDistribution data distribution"
                )

                startAllNodes(nodes, locationsMap, logsPath)

                sleep(expConfig.duration * 1000L)

                stopEverything(nodes)


                println("Changing ownership")

                nodes[0].proxy.executeCommand(
                    nodes[0].inspect.id,
                    arrayOf("chown", "-R", "$uid:$gid", "${logsPath}/")
                )

            }
        }
        removeAllContainers(proxies)
    }
}

private suspend fun startAllNodes(
    nodes: List<DockerProxy.ContainerProxy>,
    locationsMap: Map<Int, Pair<Double, Double>>,
    logsPath: String
) {
    print("Starting nodes... ")
    coroutineScope {
        val dc = nodes[0].inspect.config.hostName!!
        var index = 0
        nodes.forEach { container ->
            val hostname = container.inspect.config.hostName!!
            val location = locationsMap[hostname.split("-")[1].toInt()]!!
            val cmd = mutableListOf(
                "./start.sh",
                "$logsPath/$hostname.log",
                "hostname=$hostname",
                "region=eu",
                "datacenter=$dc",
                "location_x=${location.first}",
                "location_y=${location.second}",
                "tree_builder_nnodes=${nodes.size}",
            )
            launch(Dispatchers.IO) {
                container.proxy.executeCommand(container.inspect.id, cmd.toTypedArray(), "/server")
            }
            index++
        }
    }
    println("done.")
}


suspend fun launchContainers(tcConfig: TcConfig, proxies: Proxies, dockerConfig: DockerConfig) {
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
    for (containerNumber in dockerConfig.nClients - 1 downTo 0) {
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
        Bind(dockerConfig.logsFolder, logsVol),
        Bind(dockerConfig.tcFolder, tcVol, AccessMode.ro),
        Bind(dockerConfig.serverFolder, serverVol, AccessMode.ro),
        Bind(dockerConfig.clientFolder, clientVol, AccessMode.ro)
    )

    val hostConfig = HostConfig().withAutoRemove(true).withPrivileged(true).withCapAdd(Capability.SYS_ADMIN)
        .withCapAdd(Capability.NET_ADMIN).withBinds(binds).withNetworkMode(dockerConfig.networkName)

    val totalContainers = dockerConfig.nNodes + dockerConfig.nClients
    val createdChannel: Channel<String> = Channel(totalContainers)
    coroutineScope {
        async(Dispatchers.IO) {

            //println(nodeContainersInfo.map { it.key to it.value.size })
            val nodeCallbacks = (listOf(proxies.dcProxy) + proxies.nodeProxies).map {
                async(Dispatchers.IO) {
                    it.createServerContainers(
                        nodeContainersInfo[it.shortHost]!!, dockerConfig.imageTag,
                        hostConfig, volumes, tcConfig.latencyFile, dockerConfig.nNodes, createdChannel
                    )
                }
            }
            //println(clientContainersInfo.map { it.key to it.value.size })
            val clientCallbacks = proxies.clientProxies.map {
                async(Dispatchers.IO) {
                    it.createClientContainers(
                        clientContainersInfo[it.shortHost]!!, dockerConfig.imageTag,
                        hostConfig, volumes, tcConfig.latencyFile, dockerConfig.nNodes, createdChannel
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
