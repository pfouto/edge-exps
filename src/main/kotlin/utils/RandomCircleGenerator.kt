package utils

import java.text.DecimalFormat
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

const val nPoints = 300
const val circleRadius = 300
const val seed = 0
const val minDist = 15

val dec = DecimalFormat("#,###.##")

fun main(args: Array<String>) {

    var nId = 0

    val allNodes = mutableListOf<Pair<Int, Pair<Double, Double>>>()
    allNodes.add(Pair(nId, Pair(0.0, 0.0)))
    println(nId++)
    val random = Random(seed)
    for (i in 0 until nPoints - 1) {

        var x: Double
        var y: Double
        do {
            val radius = sqrt(random.nextDouble()) * circleRadius
            val theta = random.nextDouble() * 2 * Math.PI
            x = radius * cos(theta)
            y = radius * sin(theta)
        } while (!awayFromAll(x, y, allNodes))
        allNodes.add(Pair(nId, Pair(x, y)))
        println(nId++)
    }

    //Write allNodes to a file
    val file = java.io.File("nodes.txt")
    file.delete()
    allNodes.forEach {
        file.appendText("${it.first}\t${it.second.first}\t${it.second.second}\n")
    }

    var maxDist = 0.0
    val file2 = java.io.File("latencies.txt")
    file2.delete()
    allNodes.forEach {
        allNodes.forEach { other ->
            val distance = distanceTo(it.second.first, it.second.second, other.second.first, other.second.second)/3
            if(distance > maxDist) maxDist = distance
            file2.appendText("${dec.format(distance)} ")
        }
        file2.appendText("\n")
    }
    println("Max latency: $maxDist")
}

fun awayFromAll(x: Double, y: Double, allNodes: List<Pair<Int, Pair<Double, Double>>>): Boolean {
    for (node in allNodes) {
        if (distanceTo(x, y, node.second.first, node.second.second) < minDist) {
            return false
        }
    }
    return true
}

fun distanceTo(x: Double, y: Double, x2: Double, y2: Double): Double {
    return sqrt((x - x2) * (x - x2) + (y - y2) * (y - y2))
}
