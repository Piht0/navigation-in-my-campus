package com.campus.navigator

import java.util.PriorityQueue

object PathFinder {

    data class Cell(val row: Int, val col: Int)

    data class PathResult(
        val path: List<Cell>,
        val animEvents: IntArray,
        val found: Boolean
    )

    private const val COST_STRAIGHT = 1.0f
    private const val COST_DIAGONAL = 1.4142f

    private val DIRECTIONS = arrayOf(
        intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1),
        intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1)
    )

    private var gScore = FloatArray(0)
    private var parentIdx = IntArray(0)
    private var visitedArr = BooleanArray(0)
    private val touchedIndices = mutableListOf<Int>()
    private val animEventList = mutableListOf<Int>()

    private fun ensureArrays() {
        val size = MapData.ROWS * MapData.COLS
        if (gScore.size != size) {
            gScore     = FloatArray(size) { Float.MAX_VALUE }
            parentIdx  = IntArray(size)  { -1 }
            visitedArr = BooleanArray(size)
        }
    }

    private fun resetTouched() {
        for (i in touchedIndices) {
            gScore[i]     = Float.MAX_VALUE
            parentIdx[i]  = -1
            visitedArr[i] = false
        }
        touchedIndices.clear()
        animEventList.clear()
    }

    private fun encode(row: Int, col: Int) = row * MapData.COLS + col
    private fun decodeRow(idx: Int) = idx / MapData.COLS
    private fun decodeCol(idx: Int) = idx % MapData.COLS

    fun nearestPassable(row: Int, col: Int, maxRadius: Int = 40): Cell? {
        if (MapData.isPassable(row, col)) return Cell(row, col)
        val visited = HashSet<Int>()
        val queue   = ArrayDeque<Cell>()
        queue.add(Cell(row, col))
        visited.add(row * MapData.COLS + col)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (Math.abs(cur.row - row) > maxRadius || Math.abs(cur.col - col) > maxRadius) continue
            for (dir in DIRECTIONS) {
                val nr = cur.row + dir[0]; val nc = cur.col + dir[1]
                if (!MapData.isInBounds(nr, nc)) continue
                val key = nr * MapData.COLS + nc
                if (!visited.add(key)) continue
                if (MapData.isPassable(nr, nc)) return Cell(nr, nc)
                queue.add(Cell(nr, nc))
            }
        }
        return null
    }

    fun findPathMulti(start: Cell, waypoints: List<Cell>): List<Cell> {
        if (waypoints.isEmpty()) return emptyList()
        val snap   = { c: Cell -> nearestPassable(c.row, c.col) ?: c }
        val result = mutableListOf<Cell>()
        var from   = snap(start)
        for (raw in waypoints) {
            val to = snap(raw)
            val r  = findPath(from, to)
            if (r.found) result.addAll(r.path)
            from = to
        }
        return result
    }

    @Synchronized
    fun findPathCost(start: Cell, end: Cell): Float {
        if (!MapData.isPassable(start.row, start.col) || !MapData.isPassable(end.row, end.col))
            return Float.MAX_VALUE

        ensureArrays()
        resetTouched()

        val startIdx = encode(start.row, start.col)
        val endIdx   = encode(end.row,   end.col)

        gScore[startIdx] = 0f
        touchedIndices.add(startIdx)

        val openQueue = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        openQueue.add(Pair(heuristic(start.row, start.col, end), startIdx))

        while (openQueue.isNotEmpty()) {
            val (_, curIdx) = openQueue.poll()!!
            if (visitedArr[curIdx]) continue
            visitedArr[curIdx] = true

            if (curIdx == endIdx) return gScore[endIdx]

            val curRow = decodeRow(curIdx); val curCol = decodeCol(curIdx)
            for (dir in DIRECTIONS) {
                val nRow = curRow + dir[0]; val nCol = curCol + dir[1]
                if (!MapData.isPassable(nRow, nCol)) continue
                val nIdx = encode(nRow, nCol)
                if (visitedArr[nIdx]) continue
                val moveCost   = if (dir[0] != 0 && dir[1] != 0) COST_DIAGONAL else COST_STRAIGHT
                val tentativeG = gScore[curIdx] + moveCost
                if (tentativeG < gScore[nIdx]) {
                    if (gScore[nIdx] == Float.MAX_VALUE) touchedIndices.add(nIdx)
                    gScore[nIdx]    = tentativeG
                    parentIdx[nIdx] = curIdx
                    openQueue.add(Pair(tentativeG + heuristic(nRow, nCol, end), nIdx))
                }
            }
        }
        return Float.MAX_VALUE
    }

    @Synchronized
    fun findPath(start: Cell, end: Cell): PathResult {
        if (!MapData.isPassable(start.row, start.col) || !MapData.isPassable(end.row, end.col))
            return PathResult(emptyList(), IntArray(0), false)

        ensureArrays()
        resetTouched()

        val startIdx = encode(start.row, start.col)
        val endIdx   = encode(end.row,   end.col)

        gScore[startIdx] = 0f
        touchedIndices.add(startIdx)
        animEventList.add(startIdx)

        val openQueue = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        openQueue.add(Pair(heuristic(start.row, start.col, end), startIdx))

        while (openQueue.isNotEmpty()) {
            val (_, curIdx) = openQueue.poll()!!
            if (visitedArr[curIdx]) continue
            visitedArr[curIdx] = true
            animEventList.add(-(curIdx + 1))

            if (curIdx == endIdx) {
                return PathResult(
                    reconstructPath(startIdx, endIdx),
                    animEventList.toIntArray(),
                    true
                )
            }

            val curRow = decodeRow(curIdx)
            val curCol = decodeCol(curIdx)

            for (dir in DIRECTIONS) {
                val nRow = curRow + dir[0]
                val nCol = curCol + dir[1]
                if (!MapData.isPassable(nRow, nCol)) continue
                val nIdx = encode(nRow, nCol)
                if (visitedArr[nIdx]) continue

                val moveCost   = if (dir[0] != 0 && dir[1] != 0) COST_DIAGONAL else COST_STRAIGHT
                val tentativeG = gScore[curIdx] + moveCost

                if (tentativeG < gScore[nIdx]) {
                    val firstTouch = gScore[nIdx] == Float.MAX_VALUE
                    gScore[nIdx]    = tentativeG
                    parentIdx[nIdx] = curIdx
                    if (firstTouch) {
                        touchedIndices.add(nIdx)
                        animEventList.add(nIdx)
                    }
                    openQueue.add(Pair(tentativeG + heuristic(nRow, nCol, end), nIdx))
                }
            }
        }
        return PathResult(emptyList(), animEventList.toIntArray(), false)
    }

    private fun heuristic(fromRow: Int, fromCol: Int, to: Cell): Float {
        val dr = Math.abs(fromRow - to.row).toFloat()
        val dc = Math.abs(fromCol - to.col).toFloat()
        return COST_STRAIGHT * (dr + dc) + (COST_DIAGONAL - 2 * COST_STRAIGHT) * Math.min(dr, dc)
    }

    private fun reconstructPath(startIdx: Int, endIdx: Int): List<Cell> {
        val path = mutableListOf<Cell>()
        var idx  = endIdx
        while (idx != startIdx && idx != -1) {
            path.add(Cell(decodeRow(idx), decodeCol(idx)))
            idx = parentIdx[idx]
        }
        path.add(Cell(decodeRow(startIdx), decodeCol(startIdx)))
        path.reverse()
        return path
    }
}
