package com.campus.navigator

import kotlin.math.sqrt

object KMeansClusterer {

    const val DEFAULT_MAX_DIST = 135f

    data class ClusterResult(
        val assignments: Map<Pair<Int, Int>, Int>,
        val centroids: List<Pair<Float, Float>>,
        val singletonIds: Set<Int>
    )

    fun cluster(
        points: List<Pair<Int, Int>>,
        maxDist: Float = DEFAULT_MAX_DIST
    ): ClusterResult {
        val n = points.size
        if (n == 0) return ClusterResult(emptyMap(), emptyList(), emptySet())

        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var i = x
            while (parent[i] != i) { val t = parent[i]; parent[i] = r; i = t }
            return r
        }

        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        for (i in 0 until n)
            for (j in i + 1 until n)
                if (dist(points[i], points[j]) <= maxDist)
                    union(i, j)

        val rootToId = LinkedHashMap<Int, Int>()
        val assignments = HashMap<Pair<Int, Int>, Int>()
        for (i in 0 until n) {
            val root = find(i)
            val id = rootToId.getOrPut(root) { rootToId.size }
            assignments[points[i]] = id
        }

        val groups = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for ((pt, id) in assignments) groups.getOrPut(id) { mutableListOf() }.add(pt)
        val centroids = (0 until rootToId.size).map { id ->
            val pts = groups[id] ?: emptyList()
            Pair(
                pts.sumOf { it.first }.toFloat() / pts.size,
                pts.sumOf { it.second }.toFloat() / pts.size
            )
        }
        val singletonIds = groups.entries.filter { it.value.size == 1 }.map { it.key }.toSet()

        return ClusterResult(assignments, centroids, singletonIds)
    }

    private fun dist(a: Pair<Int, Int>, b: Pair<Int, Int>): Float {
        val dr = (a.first - b.first).toFloat()
        val dc = (a.second - b.second).toFloat()
        return sqrt(dr * dr + dc * dc)
    }
}
