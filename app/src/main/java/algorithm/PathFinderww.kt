package com.campus.navigator.algorithm

import com.campus.navigator.data.MapData
import java.util.*
import kotlin.math.*

object PathFinderww {

    data class Cell(val row: Int, val col: Int)

    data class PathResult(
        val path: List<Cell>,
        val visited: Set<Cell>,
        val found: Boolean
    )

    fun findPath(start: Cell, end: Cell): PathResult {
        val rows = MapData.ROWS
        val cols = MapData.COLS

        if (!MapData.isPassable(start.row, start.col) || !MapData.isPassable(end.row, end.col)) {
            return PathResult(emptyList(), emptySet(), false)
        }

        val openSet = PriorityQueue<Node>(compareBy { it.f })
        val closedSet = mutableSetOf<Cell>()
        val gScore = FloatArray(rows * cols) { Float.MAX_VALUE }
        val parentMap = arrayOfNulls<Cell>(rows * cols)

        val startIdx = start.row * cols + start.col
        gScore[startIdx] = 0f
        openSet.add(Node(start, 0f, heuristic(start, end)))

        while (openSet.isNotEmpty()) {
            val current = openSet.poll()!!
            val currentCell = current.cell
            val currentIdx = currentCell.row * cols + currentCell.col

            if (currentCell == end) {
                return PathResult(
                    path = reconstructPath(parentMap, end, cols),
                    visited = closedSet,
                    found = true
                )
            }

            if (current.g > gScore[currentIdx]) continue

            closedSet.add(currentCell)

            for (dr in -1..1) {
                for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue

                    val nr = currentCell.row + dr
                    val nc = currentCell.col + dc

                    if (MapData.isPassable(nr, nc)) {
                        val neighbor = Cell(nr, nc)
                        if (neighbor in closedSet) continue

                        val weight = if (dr != 0 && dc != 0) 1.4142f else 1.0f
                        val tentativeG = gScore[currentIdx] + weight
                        val nIdx = nr * cols + nc

                        if (tentativeG < gScore[nIdx]) {
                            gScore[nIdx] = tentativeG
                            parentMap[nIdx] = currentCell
                            openSet.add(Node(neighbor, tentativeG, heuristic(neighbor, end)))
                        }
                    }
                }
            }
        }

        return PathResult(emptyList(), closedSet, false)
    }

    private class Node(
        val cell: Cell,
        val g: Float,
        val h: Float,
        val f: Float = g + h
    )

    private fun reconstructPath(parentMap: Array<Cell?>, end: Cell, cols: Int): List<Cell> {
        val path = mutableListOf<Cell>()
        var current: Cell? = end
        while (current != null) {
            path.add(0, current)
            val idx = current.row * cols + current.col
            current = parentMap[idx]
        }
        return path
    }

    private fun heuristic(a: Cell, b: Cell): Float {
        val dx = abs(a.col - b.col).toFloat()
        val dy = abs(a.row - b.row).toFloat()
        val dMin = min(dx, dy)
        val dMax = max(dx, dy)
        return 1.4142f * dMin + (dMax - dMin)
    }
}
