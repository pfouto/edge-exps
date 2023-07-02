package latency

import kotlinx.serialization.Serializable

@Serializable
data class LatencyConfig(
    val name: String,
    val type: String,
    val tcSetup: List<String>,
    val nodes: List<Int>,
    val duration: Int,
    val dataDistribution: List<String>,
    val partitions: Map<Int, String>,
    val threads: Int,
    val limit: Int,
    val readPercents: List<Int>,
    val clientPersistence: List<Int>,
    val recordCount: Int,
    val propagateTimeout: Int,
)