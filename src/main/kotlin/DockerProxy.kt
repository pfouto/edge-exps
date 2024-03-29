import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.*
import java.util.*


class DockerProxy(private val host: String) {

    private var client: DockerClient
    val shortHost: String = host.split(".").first()

    private val runningCmds = Collections.synchronizedList(mutableListOf<String>())

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
        //println("Created DockerProxy for $shortHost")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DockerProxy) return false
        return host == other.host
    }

    override fun hashCode(): Int {
        return host.hashCode()
    }

    fun reconnect() {
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
        //println("Re-created DockerProxy for $shortHost")
    }

    fun removeAllContainers() {
        val containers = client.listContainersCmd().exec()
        //println("Removing ${containers.size} containers on $shortHost")
        containers.forEach { client.removeContainerCmd(it.id).withForce(true).exec() }
    }

    fun leaveSwarm() {
        println("Leaving swarm on $shortHost")
        try {
            client.leaveSwarmCmd().withForceEnabled(true).exec()
        } catch (e: DockerException) {
            if (e.httpStatus == 503)
            //println("Swarm already left on $shortHost")
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

    fun amSwarmManager(): Boolean {
        try {
            client.inspectSwarmCmd().exec()
            return true
        } catch (e: DockerException) {
            if (e.httpStatus == 503)
                return false
            throw e
        }
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

    fun cp(resource: String, dest: String) {
        val container = listContainers().first()
        //println("Copying $resource from $shortHost ${container.inspect.name} to $dest")
        try {
            val exec = client.copyArchiveFromContainerCmd(container.inspect.id, resource).exec()
            TarArchiveInputStream(exec).use { tarStream -> unTar(tarStream, File(dest)) }
        } catch (e: NotFoundException) {
            if (e.httpStatus == 404) {
                //println("No logs in $shortHost ${container.inspect.name}")
            } else
                throw e
        }
    }

    private fun unTar(tis: TarArchiveInputStream, destFolder: File) {
        var next: TarArchiveEntry?
        while (tis.nextTarEntry.also { next = it } != null) {
            val tarEntry = next!!
            val outputFile = File(destFolder, tarEntry.name)
            if (tarEntry.isDirectory) {
                if (!outputFile.exists()) {
                    outputFile.mkdirs()
                }
            } else {
                val outputStream = FileOutputStream(outputFile)
                IOUtils.copy(tis, outputStream)
            }
        }
        tis.close()
    }

    fun loadImage(imageLoc: String) {
        //println("Loading image $imageLoc on $shortHost")
        client.loadImageCmd(File(imageLoc).inputStream()).exec()
    }

    suspend fun createServerContainers(
        pairs: MutableList<Pair<Int, String>>, imageTag: String, hostConfigFirst: HostConfig,
        hostConfigRest: HostConfig, volumes: Volumes, latencyFile: String, nServers: Int, channel: Channel<String>,
    ) {
        //println("Creating ${pairs.size} containers on $shortHost")
        pairs.forEach { (number, ip) ->
            if (number == 0)
                createContainer(
                    "node", number, ip, imageTag, hostConfigFirst, volumes,
                    latencyFile, nServers, channel, 0, 10000, 1
                )
            else
                createContainer(
                    "node", number, ip, imageTag, hostConfigRest, volumes, latencyFile,
                    nServers, channel, 0, 1000, 1
                )
        }
    }

    suspend fun createServerContainersCassandra(
        pairs: MutableList<Pair<Int, String>>, imageTag: String, quotaLimit: Long, networkName: String,
        baseBinds: Binds, volumes: Volumes, latencyFile: String, nServers: Int, channel: Channel<String>,
        cassVolume: Volume
    ) {


        //println("Creating ${pairs.size} containers on $shortHost")
        pairs.forEach { (number, ip) ->
            val bindList = baseBinds.binds + Bind("/mnt/ramdisk/cassandra/node-$number", cassVolume)
            val binds = Binds(*bindList)

            if (number == 0) {
                val hostConfigFull = HostConfig().withAutoRemove(true).withPrivileged(true)
                    .withCapAdd(Capability.SYS_ADMIN).withCapAdd(Capability.NET_ADMIN).withBinds(binds)
                    .withNetworkMode(networkName)

                createContainer(
                    "node", number, ip, imageTag, hostConfigFull, volumes,
                    latencyFile, nServers, channel, 0, 10000, 1
                )
            } else {
                val hostConfigLimited = HostConfig().withAutoRemove(true).withPrivileged(true)
                    .withCapAdd(Capability.SYS_ADMIN).withCapAdd(Capability.NET_ADMIN).withBinds(binds)
                    .withNetworkMode(networkName).withCpuQuota(200000)
                createContainer(
                    "node", number, ip, imageTag, hostConfigLimited, volumes, latencyFile,
                    nServers, channel, 0, 1000, 1
                )
            }
        }
    }


    suspend fun createClientContainers(
        pairs: MutableList<Pair<Int, String>>, imageTag: String, hostConfig: HostConfig,
        volumes: Volumes, latencyFile: String, nServers: Int, channel: Channel<String>,
        overrideEntryPoint: String? = null,
    ) {
        //println("Creating ${pairs.size} containers on $shortHost")
        pairs.forEach { (number, ip) ->
            createContainer(
                "client", number, ip, imageTag, hostConfig, volumes, latencyFile,
                nServers, channel, 5, 10000, 2,
                overrideEntryPoint = overrideEntryPoint
            )
        }
    }

    private suspend fun createContainer(
        baseName: String, id: Int, ip: String, image: String, hostConfig: HostConfig, volumes: Volumes,
        latencyFile: String, nServers: Int, channel: Channel<String>, selfLatency: Int, bandwidth: Int,
        latencyMultiplier: Int, overrideEntryPoint: String? = null,
    ) {
        //println("Creating container $id on $shortHost")
        val name = "$baseName-$id"


        val command = client.createContainerCmd(image).withName(name).withHostName(name).withTty(true)
            .withAttachStderr(false).withAttachStdout(false)
            .withAttachStdin(false).withHostConfig(hostConfig).withVolumes(volumes.volumes.toList())
            .withCmd(
                id.toString(), latencyFile, nServers.toString(), selfLatency.toString(), bandwidth.toString(),
                latencyMultiplier.toString()
            ).withIpv4Address(ip)

        if (overrideEntryPoint != null) {
            command.withEntrypoint(overrideEntryPoint)
        }

        val cId = command.exec()
        client.startContainerCmd(cId.id).exec()

        channel.send(cId.id)
    }

    fun listContainers(): List<ContainerProxy> {
        val cList = client.listContainersCmd().exec()
        val detailedList = cList.map { client.inspectContainerCmd(it.id).exec() }
        return detailedList.map { ContainerProxy(it, this) }

    }

    fun executeCommandSync(cId: String, cmd: Array<String>): String {

        val createCmd = client.execCreateCmd(cId).withCmd(*cmd).withAttachStdout(true)
            .withAttachStderr(true)

        val createExec = createCmd.exec()

        val exec = client.execStartCmd(createExec.id).withDetach(false)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(item: Frame?) {
                    println(item?.toString())
                }
            })
        exec.awaitCompletion()
        return createExec.id
    }

    fun executeCommand(cId: String, cmd: Array<String>, workDir: String = "/", env: List<String>? = null): String {

        val createCmd = client.execCreateCmd(cId).withCmd(*cmd).withWorkingDir(workDir)
        if(env != null)
            createCmd.withEnv(env)

        val createExec = createCmd.exec()

        val exec = client.execStartCmd(createExec.id).withDetach(true)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(item: Frame?) {
                    println(item?.toString())
                }
            })
        exec.awaitCompletion()
        runningCmds.add(createExec.id)
        return createExec.id
    }

    fun waitAllRunningCmds() {
        while (runningCmds.isNotEmpty()) {
            runningCmds.removeIf {
                try {
                    !client.inspectExecCmd(it).exec().isRunning
                } catch (e: DockerException) {
                    if (e.httpStatus == 404)
                        true
                    else
                        throw e
                }
            }
            Thread.sleep(500)
        }
    }

    fun close() {
        client.close()
    }

    fun deleteVolume(s: String) {
        try {
            client.removeVolumeCmd(s).exec()
        } catch (e: NotFoundException) {
            if (e.httpStatus != 404)
                throw e
        }
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
                    //println("$host exit value is ${process.exitValue()}")
                    process.exitValue()
                }
            }
            val sum = jobs.awaitAll().sum()
            if (sum != 0)
                throw IllegalStateException("Failed to install docker on all hosts")
        }

        suspend fun restartDockerService(hosts: List<String>) = coroutineScope {
            val jobs = hosts.map { host ->
                async(Dispatchers.IO) {
                    //println("Restarting docker on $host")
                    val process = Runtime.getRuntime()
                        .exec(arrayOf("ssh", "-n", host, "sudo systemctl restart docker"))

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
                    if (process.exitValue() != 0)
                        println("$host exit value is ${process.exitValue()}")
                    process.exitValue()
                }
            }
            val sum = jobs.awaitAll().sum()
            if (sum != 0)
                throw IllegalStateException("Failed to restart docker on all hosts")
        }

        suspend fun runCommand(host: String, cmd: String) = coroutineScope {

            val process = Runtime.getRuntime()
                .exec(arrayOf("ssh", "-n", host, cmd))

            process.waitFor()
            if (process.exitValue() != 0)
                println("$host exit value is ${process.exitValue()} for command $cmd")
            process.exitValue()
        }

    }

    data class ContainerProxy(val inspect: InspectContainerResponse, val proxy: DockerProxy)
}

