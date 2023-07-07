package cassandra_micro

import kotlinx.serialization.Serializable

@Serializable
data class CassandraMicroConfig(
    val name: String,
    val type: String,
    val tcSetup: List<String>,
    val nodes: List<Int>,
    val duration: Int,
    val partitions: Map<Int, String>,
    val dataDistribution: List<String>,
    val threads: List<Int>,
    val readPercents: List<Int>,
    val threadLimit: Map<Int, Map<String, Map<Int, Int>>>,
)