package com.campus.navigator

object GeneticRouteSolver {

    private const val POPULATION_SIZE = 100
    private const val GENERATIONS     = 300
    const val  MUTATION_RATE          = 0.02
    private const val TOURNAMENT_K    = 5
    private const val PROGRESS_EVERY  = 30

    data class Result(
        val orderedIndices: List<Int>,
        val totalDistanceCells: Float
    )

    fun solveWithMatrix(
        n: Int,
        startDists: FloatArray,
        distMatrix: Array<FloatArray>,
        onProgress: ((indices: List<Int>, dist: Float) -> Unit)? = null
    ): Result = solveInternal(n, startDists, distMatrix, onProgress)

    private fun solveInternal(
        n: Int,
        startDists: FloatArray,
        distMatrix: Array<FloatArray>,
        onProgress: ((indices: List<Int>, dist: Float) -> Unit)? = null
    ): Result {
        if (n == 0) return Result(emptyList(), 0f)
        if (n == 1) return Result(listOf(0), startDists[0])

        val rnd = java.util.Random()

        fun routeLen(route: IntArray): Float {
            var d = startDists[route[0]]
            for (i in 0 until route.size - 1) d += distMatrix[route[i]][route[i + 1]]
            return d
        }

        val pop = Array(POPULATION_SIZE) {
            IntArray(n) { it }.also { a ->
                for (i in n - 1 downTo 1) {
                    val j = rnd.nextInt(i + 1)
                    val t = a[i]; a[i] = a[j]; a[j] = t
                }
            }
        }

        val fitness = FloatArray(POPULATION_SIZE) { routeLen(pop[it]) }

        fun tournament(): IntArray {
            var bestIdx = rnd.nextInt(POPULATION_SIZE)
            repeat(TOURNAMENT_K - 1) {
                val idx = rnd.nextInt(POPULATION_SIZE)
                if (fitness[idx] < fitness[bestIdx]) bestIdx = idx
            }
            return pop[bestIdx].copyOf()
        }

        val seenArr = BooleanArray(n)

        fun ox(p1: IntArray, p2: IntArray): IntArray {
            val lo    = rnd.nextInt(n)
            val hi    = lo + rnd.nextInt(n - lo)
            val child = IntArray(n) { -1 }
            for (i in lo..hi) { child[i] = p1[i]; seenArr[p1[i]] = true }
            var ci = (hi + 1) % n
            for (k in 0 until n) {
                val g = p2[(hi + 1 + k) % n]
                if (!seenArr[g]) { child[ci] = g; ci = (ci + 1) % n }
            }
            for (i in lo..hi) seenArr[p1[i]] = false
            return child
        }

        fun mutate(r: IntArray): IntArray {
            if (rnd.nextDouble() >= MUTATION_RATE) return r
            val i = rnd.nextInt(n); val j = rnd.nextInt(n)
            val t = r[i]; r[i] = r[j]; r[j] = t
            return r
        }

        var globalBestIdx = fitness.indices.minByOrNull { fitness[it] }!!
        var globalBest    = pop[globalBestIdx].copyOf()
        var globalBestLen = fitness[globalBestIdx]

        repeat(GENERATIONS) { gen ->
            for (i in pop.indices) {
                pop[i] = mutate(ox(tournament(), tournament()))
                fitness[i] = routeLen(pop[i])
                if (fitness[i] < globalBestLen) {
                    globalBestLen = fitness[i]
                    globalBest    = pop[i].copyOf()
                }
            }

            if (onProgress != null && (gen + 1) % PROGRESS_EVERY == 0) {
                onProgress(globalBest.toList(), globalBestLen)
            }
        }

        return Result(globalBest.toList(), globalBestLen)
    }

}
