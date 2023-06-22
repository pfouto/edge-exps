package utils

import kotlinx.serialization.Serializable

@Serializable
data class TcConfig (
    val latencyFile: String,
    val nodesFile: String
)