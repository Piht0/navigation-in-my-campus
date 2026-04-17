package com.campus.navigator

object AntColonySolver {

    // Параметры алгоритма
    private const val N_ANTS = 25
    private const val N_ITERATIONS = 180
    private const val ALPHA = 1.0
    private const val BETA = 4.0
    private const val RHO = 0.15
    private const val Q = 100.0
    private const val ELITIST_WEIGHT = 3.0
    private const val INF = 500000f

    data class Result(
        val orderedIndices: List<Int>,
        val totalDistance: Float
    )

    fun solve(n: Int, startDists: FloatArray, distMatrix: Array<FloatArray>): Result {
        if (n == 0) return Result(emptyList(), 0f)
        if (n == 1) return Result(listOf(0), startDists[0])

        println("Запуск муравьиного алгоритма для $n точек") // Для отладки
        val rnd = java.util.Random()

        fun routeLen(route: IntArray): Float {
            var d = startDists[route[0]]
            for (i in 0 until route.size - 1) {
                d += distMatrix[route[i]][route[i + 1]]
            }
            return d
        }

        val tau = Array(n) { DoubleArray(n) { 1.0 } }
        val eta = Array(n) { i ->
            DoubleArray(n) { j ->
                if (i == j || distMatrix[i][j] >= INF / 2) 0.000001
                else 1.0 / distMatrix[i][j]
            }
        }
        val etaStart = DoubleArray(n) { i ->
            if (startDists[i] >= INF / 2) 0.000001 else 1.0 / startDists[i]
        }

        var bestRoute = IntArray(n) { it }
        var bestLen = routeLen(bestRoute)

        val etaStartB = DoubleArray(n) { i -> Math.pow(etaStart[i], BETA) }

        val visited = BooleanArray(n)
        val route = IntArray(n)
        val firstProbs = DoubleArray(n)
        val probs = DoubleArray(n)

        for (iter in 0 until N_ITERATIONS) {
            val antRoutes = Array(N_ANTS) { IntArray(0) }

            for (ant in 0 until N_ANTS) {
                visited.fill(false)

                // Выбор первой точки
                for (i in 0 until n) {
                    firstProbs[i] = etaStartB[i]
                }
                route[0] = rouletteSelect(firstProbs, rnd)
                visited[route[0]] = true

                // Строим путь
                for (step in 1 until n) {
                    val cur = route[step - 1]
                    for (j in 0 until n) {
                        if (visited[j]) {
                            probs[j] = 0.0
                        } else {
                            val e = eta[cur][j]
                            probs[j] = tau[cur][j] * Math.pow(e, BETA)
                        }
                    }
                    route[step] = rouletteSelect(probs, rnd)
                    visited[route[step]] = true
                }

                antRoutes[ant] = route.copyOf()
                val len = routeLen(route)
                if (len < bestLen) {
                    bestLen = len
                    bestRoute = route.copyOf()
                    // println("Новый лучший путь: $bestLen")
                }
            }

            // Испарение феромона
            for (i in 0 until n) {
                for (j in 0 until n) {
                    tau[i][j] = tau[i][j] * (1.0 - RHO)
                    if (tau[i][j] < 0.000001) tau[i][j] = 0.000001
                }
            }

            // Обновление феромона
            for (r in antRoutes) {
                val deposit = Q / routeLen(r)
                for (i in 0 until r.size - 1) {
                    tau[r[i]][r[i + 1]] += deposit
                    tau[r[i + 1]][r[i]] += deposit
                }
            }

            // Добавляем элитных муравьев
            val eliteDeposit = Q * ELITIST_WEIGHT / bestLen
            for (i in 0 until bestRoute.size - 1) {
                tau[bestRoute[i]][bestRoute[i + 1]] += eliteDeposit
                tau[bestRoute[i + 1]][bestRoute[i]] += eliteDeposit
            }
        }

        println("Алгоритм завершен. Дистанция: $bestLen")
        return Result(bestRoute.toList(), bestLen)
    }

    private fun rouletteSelect(probs: DoubleArray, rnd: java.util.Random): Int {
        val sum = probs.sum()
        if (sum <= 0.0) {
            // Если вероятности нулевые, выбираем рандом из не посещенных
            val list = mutableListOf<Int>()
            for (i in probs.indices) {
                if (probs[i] >= 0) list.add(i)
            }
            return if (list.isNotEmpty()) list[rnd.nextInt(list.size)] else 0
        }
        var r = rnd.nextDouble() * sum
        for (i in probs.indices) {
            r -= probs[i]
            if (r <= 0.0) return i
        }
        return probs.size - 1
    }
}
