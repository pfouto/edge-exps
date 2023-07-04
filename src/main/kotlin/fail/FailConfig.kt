package fail

import com.google.common.math.IntMath
import kotlinx.serialization.Serializable

@Serializable
data class FailConfig(
    val name: String,
    val type: String,
    val failAt: Int,
    val failPercents: List<Int>,
    val tcSetup: List<String>,
    val nodes: List<Int>,
    val duration: Int,
    val dataDistribution: List<String>,
    val partitions: Map<Int, String>,
    val threads: Int,
    val limit: Int,
    val readPercents: List<Int>,
    val clientPersistence: List<Int>,
    val propagateTimeout: Int,
    val modes: List<String>,
    val steps: Int,
    val stepInterval: Int,
)