package cassandra

import kotlinx.serialization.Serializable

@Serializable
data class CassandraConfig(
    val name: String,
    val type: String,
    val tcSetup: List<String>,
    val nodes: List<Int>,
    val duration: Int,
    val partitions: Map<Int, String>,
    val dataDistribution: List<String>,

    )