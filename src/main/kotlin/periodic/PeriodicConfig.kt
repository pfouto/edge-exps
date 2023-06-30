package periodic

import kotlinx.serialization.Serializable

@Serializable
data class PeriodicConfig(
    val name: String,
    val type: String,
    val tcSetup: List<String>,
    val nodes: List<Int>,
    val duration: Int,
    val dataDistribution: String,
    val partitions: Map<Int, String>,
    val threads: Int,
    val limit: Int,
    val readPercents: List<Int>,
    val periodicRemoteInterval: Long,
    val periodicRemoteDuration: Long,
    val recordCount: Int
)