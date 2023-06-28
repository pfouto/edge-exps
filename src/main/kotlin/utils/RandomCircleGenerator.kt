package utils

import java.text.DecimalFormat
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

const val nPoints = 200
const val circleRadius = 300
const val seed = 3
const val minDist = 30
const val nSlices = 8

val dec = DecimalFormat("#,###.##")

data class Node(val id: Int, val x: Double, val y: Double, val slice: Int)

fun main(args: Array<String>) {

    var nId = 0

    val allNodes = mutableListOf<Node>()
    allNodes.add(Node(nId, 0.0, 0.0, -1))
    println(nId++)
    val random = Random(seed)
    for (i in 0 until nPoints - 1) {

        var x: Double
        var y: Double
        var theta: Double
        do {
            val radius = sqrt(random.nextDouble()) * circleRadius
            theta = random.nextDouble() * 2 * Math.PI
            x = radius * cos(theta)
            y = radius * sin(theta)
        } while (!awayFromAll(x, y, allNodes))
        val slice = (theta / (2 * Math.PI) * nSlices).toInt()
        allNodes.add(Node(nId, x, y, slice))
        println(nId++)
    }

    //Write allNodes to a file
    val file = java.io.File("nodes_$seed.txt")
    file.delete()
    allNodes.forEach {
        file.appendText("${it.id}\t${it.x}\t${it.y}\t${it.slice}\n")
    }

    var maxDist = 0.0
    var minDist = Double.MAX_VALUE

    val file2 = java.io.File("latencies_$seed.txt")
    file2.delete()
    allNodes.forEach {
        allNodes.forEach { other ->
            val distance = distanceTo(it.x, it.y, other.x, other.y)/3
            if(distance > maxDist) maxDist = distance
            if(distance < minDist && distance != 0.0) minDist = distance
            file2.appendText("${dec.format(distance)} ")
        }
        file2.appendText("\n")
    }
    println(allNodes.map { it.slice }.groupBy { it }.map { Pair(it.key, it.value.size) })
    println("Max latency: $maxDist")
    println("Min latency: $minDist")
}

fun awayFromAll(x: Double, y: Double, allNodes: List<Node>): Boolean {
    for (node in allNodes) {
        if (distanceTo(x, y, node.x, node.y) < minDist) {
            return false
        }
    }
    return true
}

fun distanceTo(x: Double, y: Double, x2: Double, y2: Double): Double {
    return sqrt((x - x2) * (x - x2) + (y - y2) * (y - y2))
}
