package migration

import kotlinx.serialization.Serializable

@Serializable
data class MigrationConfig(
    val name: String,
    val type: String,
    val tcSetup: List<String>,
    val nodes: List<Int>,
    val duration: Int,
    val dataDistribution: List<String>,
    val partitions: Map<Int, String>,
    val threads: Int,
    val readPercents: List<Int>,
)