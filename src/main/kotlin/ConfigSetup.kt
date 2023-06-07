import kotlinx.serialization.Serializable

@Serializable
data class ConfigSetup (
    val setup: Setup,
)

@Serializable
data class Setup (
    val nNodes: Int,
    val maxNodesPerMachine: Int,
    val nodeMachines: String,

    val nClients: Int,
    val maxClientsPerMachine: Int,
    val clientMachines: String,

    val imageLoc: String,
    val imageTag: String,
    val logsFolder: String,
    val codeFolder: String,
    val tcFolder: String,
    val latencyFile: String,
    val networkName: String,
    val subnet: String,
    val gateway: String,
)