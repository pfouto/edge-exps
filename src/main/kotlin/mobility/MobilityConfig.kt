package mobility

import kotlinx.serialization.Serializable

@Serializable
data class MobilityConfig(
    val name: String,
    val type: String,
    val tcSetup: List<String>,
    val nodes: List<Int>,
    val duration: Int,
    val dataDistribution: List<String>,
    val partitions: Map<Int, String>,
    val threads: Int,
    val readPercents: List<Int>,
    val migrationPattern: List<String>,
    val randomDegrees: Int,
    val randomStart: Int,
    val randomDuration: Int,
    val limit: Int,

    val gcThreshold: Int,
    val gcInterval: Int,
    val recordCount: Int,

    val commuteWork: Int,
    val commuteHome: Int,
    val commuteDuration: Int,
    val workRadius: Int,
)