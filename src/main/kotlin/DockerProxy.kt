import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.IllegalStateException
import java.util.*

class DockerProxy(private val host: String) {

    private val client: DockerClient
    val shortHost: String = host.split(".").first()

    val runningCmds = Collections.synchronizedList(mutableListOf<String>())

    init {
        val dockerClientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost("tcp://${host}:2376")
            .withDockerTlsVerify(false)
            .build()

        val httpClient = ApacheDockerHttpClient.Builder()
            .dockerHost(dockerClientConfig.dockerHost)
            .sslConfig(dockerClientConfig.sslConfig)
            .build()

        client = DockerClientImpl.getInstance(dockerClientConfig, httpClient)
        client.pingCmd().exec()
        println("Created DockerProxy for $shortHost")
    }

    fun removeAllContainers() {
        val containers = client.listContainersCmd().exec()
        println("Removing ${containers.size} containers on $shortHost")
        containers.forEach { client.removeContainerCmd(it.id).withForce(true).exec() }
    }

    fun leaveSwarm() {
        println("Leaving swarm on $shortHost")
        try {
            client.leaveSwarmCmd().withForceEnabled(true).exec()
        } catch (e: DockerException) {
            if (e.httpStatus == 503)
                println("Swarm already left on $shortHost")
            else
                throw e
        }
    }

    fun initSwarm(): SwarmJoinTokens {
        client.initializeSwarmCmd(SwarmSpec()).exec()
        println("Swarm created on $shortHost")
        return client.inspectSwarmCmd().exec().joinTokens!!
    }

    fun joinSwarm(tokens: SwarmJoinTokens, leader: String) {
        client.joinSwarmCmd().withRemoteAddrs(listOf(leader)).withJoinToken(tokens.worker).exec()
        println("Joined swarm on $shortHost")
    }

    fun getSwarmMembers(): List<String> {
        return client.listSwarmNodesCmd().exec().map { it.description!!.hostname!! }
    }

    fun listNetworks(): List<String> {
        return client.listNetworksCmd().exec().map { it.name }
    }

    fun createOverlayNetwork(name: String, subnet: String, gateway: String) {
        val ipam = Network.Ipam().withConfig(
            Network.Ipam.Config().withSubnet(subnet).withGateway(gateway)
        )

        client.createNetworkCmd()
            .withName(name)
            .withIpam(ipam)
            .withAttachable(true)
            .withCheckDuplicate(true)
            .withDriver("overlay").exec()
    }

    fun loadImage(imageLoc: String) {
        println("Loading image $imageLoc on $shortHost")
        client.loadImageCmd(java.io.File(imageLoc).inputStream()).exec()
    }

    suspend fun createContainers(
        pairs: MutableList<Pair<String, String>>, imageTag: String, hostConfig: HostConfig,
        volumes: Volumes, latencyFile: String, nContainers: Int, channel: Channel<String>,
    ) {
        println("Creating ${pairs.size} containers on $shortHost")
        pairs.forEach { (number, ip) ->
            createContainer(number, ip, imageTag, hostConfig, volumes, latencyFile, nContainers, channel)
        }
    }

    private suspend fun createContainer(
        id: String, ip: String, image: String, hostConfig: HostConfig, volumes: Volumes,
        latencyFile: String, nContainers: Int, channel: Channel<String>,
    ) {
        //println("Creating container $id on $shortHost")
        val name = "node-$id"

        val cId = client.createContainerCmd(image).withName(name).withHostName(name).withTty(true)
            .withAttachStderr(false).withAttachStdout(false)
            .withAttachStdin(false).withHostConfig(hostConfig).withVolumes(volumes.volumes.toList())
            .withCmd(id, latencyFile, nContainers.toString()).withIpv4Address(ip)
            .exec()

        client.startContainerCmd(cId.id).exec()

        channel.send(cId.id)
    }

    fun listContainers(): List<ContainerProxy> {
        val cList = client.listContainersCmd().exec()
        val detailedList = cList.map { client.inspectContainerCmd(it.id).exec() }
        return detailedList.map { ContainerProxy(it, this) }

    }

    fun executeCommand(cId: String, cmd: Array<String>): String {
        val create = client.execCreateCmd(cId).withCmd(*cmd).withWorkingDir("/code").exec()
        val exec = client.execStartCmd(create.id).withDetach(true)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(item: Frame?) {
                    println(item?.toString())
                }
            })
        exec.awaitCompletion()
        runningCmds.add(create.id)
        return create.id
    }

    fun waitAllRunningCmds() {
        while(runningCmds.isNotEmpty()) {
            runningCmds.removeIf {
                !client.inspectExecCmd(it).exec().isRunning
            }
            Thread.sleep(500)
        }
    }

    fun close() {
        client.close()
    }


    companion object {
        suspend fun gridInstallDockerParallel(hosts: List<String>) = coroutineScope {
            val jobs = hosts.map { host ->
                async(Dispatchers.IO) {
                    println("Installing g5k on $host")
                    val process = Runtime.getRuntime()
                        .exec(arrayOf("oarsh", "-n", host, "sudo-g5k edge/exps/g5k-setup-docker"))

                    val inputReader = BufferedReader(InputStreamReader(process.inputStream))
                    val errorReader = BufferedReader(InputStreamReader(process.errorStream))


                    val inputJob = launch {
                        var line: String?
                        while (inputReader.readLine().also { line = it } != null)
                            println("[INPUT $host] $line")
                    }

                    val errorJob = launch {
                        var line: String?
                        while (errorReader.readLine().also { line = it } != null)
                            println("[ERROR $host] $line")
                    }

                    inputJob.join() // wait for input job to finish
                    errorJob.join() // wait for error job to finish

                    process.waitFor()
                    println("$host exit value is ${process.exitValue()}")
                    process.exitValue()
                }
            }
            val sum = jobs.awaitAll().sum()
            if (sum != 0)
                throw IllegalStateException("Failed to install docker on all hosts")
        }

    }

    data class ContainerProxy(val inspect: InspectContainerResponse, val proxy: DockerProxy)
}

