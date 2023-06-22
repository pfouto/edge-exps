package utils

import kotlinx.serialization.Serializable

@Serializable
data class DockerConfig (

    val nNodes: Int,
    val maxNodesPerMachine: Int,

    val nClients: Int,
    val maxClientsPerMachine: Int,

    val imageLoc: String,
    val imageTag: String,

    val logsFolder: String,
    val serverFolder: String,
    val clientFolder: String,
    val tcFolder: String,

    val networkName: String,
    val subnet: String,
    val gateway: String,
)