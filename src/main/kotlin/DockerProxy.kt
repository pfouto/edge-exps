import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.model.Network
import com.github.dockerjava.api.model.SwarmJoinTokens
import com.github.dockerjava.api.model.SwarmSpec
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.IllegalStateException

class DockerProxy(private val host: String) {

    private val client: DockerClient
    val shortHost: String = host.split(".").first()

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
        println("Created DockerProxy for $shortHost")
    }

    fun removeAllContainers() {
        val containers = client.listContainersCmd().exec()
        println("Removing ${containers.size} containers on $shortHost in thread ${Thread.currentThread().name}")
        containers.forEach { client.removeContainerCmd(it.id).withForce(true).exec() }
    }

    fun leaveSwarm() {
        println("Leaving swarm on $shortHost in thread ${Thread.currentThread().name}")
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
        client.joinSwarmCmd().withRemoteAddrs(listOf(leader)) .withJoinToken(tokens.worker).exec()
        println("Joined swarm on $shortHost")
    }

    fun getSwarmMembers(): List<String> {
        return client.listSwarmNodesCmd().exec().map { it.description!!.hostname!! }
    }

    fun listNetworks() : List<String> {
        return client.listNetworksCmd().exec().map { it.name }
    }

    fun createOverlayNetwork(name: String, subnet: String, gateway: String) {
        client.createNetworkCmd().withName(name)
            .withDriver("overlay")
            .withIpam(Network.Ipam()
                    .withDriver("default")
                    .withConfig(
                        listOf(
                            IpamConfig()
                                .withSubnet(subnet)
                                .withGateway(gateway)
                        )
                    )
            )
            .exec()
    }


    fun close() {
        client.close()
    }


    companion object {
        suspend fun gridInstallDockerParallel(hosts: List<String>) = coroutineScope {
            val jobs = hosts.map { host ->
                async(Dispatchers.IO) {
                    println("Installing g5k on $host in thread ${Thread.currentThread().name}")
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

                    println("$host exit value is ${process.exitValue()}")
                    process.exitValue()
                }
            }
            val sum = jobs.awaitAll().sum()
            if (sum != 0)
                throw IllegalStateException("Failed to install docker on all hosts")
        }

    }
}