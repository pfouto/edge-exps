package micro

import kotlinx.serialization.Serializable

@Serializable
data class MicroConfig(
    val name: String,
    val type: String,
    val tcSetup: List<String>,
    val nodes: List<Int>,
    val duration: Int,
    val dataDistribution: List<String>,
    val partitions: Map<Int, String>,
    val threads: List<Int>,
    val readPercents: List<Int>,
    val threadLimitPerNNodes: Map<Int, Int>
)