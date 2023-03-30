import kotlinx.serialization.Serializable

@Serializable
data class Config (
    val setup: Setup,
    val exps: List<Exp>
)

@Serializable
data class Setup (
    val nContainers: Int,
    val maxContainersPerMachine: Int,
    val imageLoc: String,
    val imageTag: String,
    val logsFolder: String,
    val codeFolder: String,
    val tcFolder: String,
    val latencyFile: String,
    val networkName: String,
    val subnet: String,
    val gateway: String
)

@Serializable
data class Exp(
    val name: String,
    val type: String,
    val skip: Boolean,
    val nodes: Map<String, Int>,
    val steps: List<Step>? = null,
    val duration: String? = null
)

@Serializable
data class Step(
    val delay: String,
    val kill: Map<String, List<Int>>? = null
)