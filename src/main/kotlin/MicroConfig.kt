import kotlinx.serialization.Serializable

@Serializable
data class MicroConfig(
    val name: String,
    val type: String,
    val skip: Boolean,
    val tcSetup: List<String>,
    val nodes: List<Int>,
    val duration: Int,
    val dataDistribution: List<String>,
    val partitions: Map<Int, String>,
    val threads: List<Int>,
    val readPercents: List<Int>,
    val periodicRemoteInterval: Long,
    val periodicRemoteDuration: Long,
)