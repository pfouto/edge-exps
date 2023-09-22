package visibility

import kotlinx.serialization.Serializable

@Serializable
data class VisibilityConfig(
    val name: String,
    val type: String,

    val duration: Int,

    val tcSetup: List<String>,
    val nodes: List<Int>,
    val readPercent: Int,

    val threads: Int,
    val limit: Int,
    val recordCount: Int,
    val dataDistribution: String,

    val treeBuilderSubList: List<String>,

    val gcThreshold: Int,
    val gcInterval: Int,

    val partitions: Map<Int, String>,


    )