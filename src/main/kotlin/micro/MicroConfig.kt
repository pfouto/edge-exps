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
    val threadLimit: Map<Int, Map<String, Map<Int, Int>>>,
    val persistence: Int,
    val locationSub: String = "deep"
)