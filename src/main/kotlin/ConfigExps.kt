import kotlinx.serialization.Serializable

@Serializable
data class ConfigExps (
    val exps: List<Exp>,
    val setupFile: String
)

@Serializable
data class Exp(
    val name: String,
    val type: String,
    val nodeLocationsFile: String,
    val skip: Boolean = false,
    val nodes: Map<String, Int>,
    val steps: List<Step> = emptyList(),
    val duration: Int? = null,
    val staticTree : String? = null
)

@Serializable
data class Step(
    val delay: Int,
    val kill: Map<String, List<Int>>? = null
)