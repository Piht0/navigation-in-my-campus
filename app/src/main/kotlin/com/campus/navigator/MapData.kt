package com.campus.navigator

import android.content.Context
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

// Точка достопримечательности на карте
data class Landmark(
    val row: Int,
    val col: Int,
    var name: String = ""
)

// Точка питания на карте
data class FoodPoint(
    val row: Int,
    val col: Int,
    var name: String = "",
    var review: String = "",
    var tags: String = "",
    var clusterId: Int = -1,
    var dbId: Long = -1L  // -1 пока не сохранена
)

// Хранилище состояния карты
object MapData {

    var ROWS = 790
        private set

    var COLS = 1664
        private set

    // Базовая матрица проходимости (биты)
    private lateinit var baseGrid: ByteArray

    private var bytesPerRow = 0  // байт на строку = ceil(COLS/8)

    // Пользовательские правки клеток: индекс → проходимость
    private val modifiedCells = HashMap<Int, Boolean>()

    private val foodPoints = mutableListOf<FoodPoint>()
    private val landmarks  = mutableListOf<Landmark>()
    private var initialized = false // защита от повторного init

    // Загружает матрицу из кэша или CSV
    fun init(context: Context) {
        if (initialized) return
        val binFile = File(context.filesDir, "passability_matrix.bin")
        if (binFile.exists()) {
            loadBinary(binFile)
        } else {
            loadCsv(context)
            try { saveBinary(binFile) } catch (_: Exception) {}
        }
        initialized = true
    }

    // Читает бинарный кэш матрицы
    private fun loadBinary(file: File) {
        DataInputStream(file.inputStream().buffered(65536)).use { dis ->
            ROWS = dis.readInt()
            COLS = dis.readInt()
            bytesPerRow = (COLS + 7) / 8
            baseGrid = ByteArray(ROWS * bytesPerRow)
            dis.readFully(baseGrid)
        }
    }

    // Сохраняет матрицу в бинарный кэш
    private fun saveBinary(file: File) {
        DataOutputStream(file.outputStream().buffered(65536)).use { dos ->
            dos.writeInt(ROWS)
            dos.writeInt(COLS)
            dos.write(baseGrid)
        }
    }

    // Парсит CSV из assets при первом запуске
    private fun loadCsv(context: Context) {
        val lines = mutableListOf<String>()
        BufferedReader(InputStreamReader(context.assets.open("passability_matrix.csv"))).use { br ->
            var line = br.readLine()
            while (line != null) {
                val t = line.trim()
                if (t.isNotEmpty()) lines.add(t)
                line = br.readLine()
            }
        }
        ROWS = lines.size
        COLS = if (lines.isNotEmpty()) lines[0].split(",").size else 0
        bytesPerRow = (COLS + 7) / 8
        baseGrid = ByteArray(ROWS * bytesPerRow)

        lines.forEachIndexed { r, line ->
            val values = line.split(",")
            values.forEachIndexed { c, v ->
                if (c < COLS && v.trim() == "1") setBit(baseGrid, r, c)
            }
        }
    }

    // Устанавливает бит проходимости в ByteArray
    private fun setBit(grid: ByteArray, row: Int, col: Int) {
        val byteIdx = row * bytesPerRow + col / 8
        val bitPos  = 7 - col % 8
        grid[byteIdx] = (grid[byteIdx].toInt() or (1 shl bitPos)).toByte()
    }

    // Читает бит базовой матрицы
    private fun baseGetBit(row: Int, col: Int): Boolean {
        val byteIdx = row * bytesPerRow + col / 8
        val bitPos  = 7 - col % 8
        return (baseGrid[byteIdx].toInt() and (1 shl bitPos)) != 0
    }

    fun isInBounds(row: Int, col: Int) = row in 0 until ROWS && col in 0 until COLS

    // Проходимость без пользовательских правок
    fun isBasePassable(row: Int, col: Int): Boolean {
        if (!isInBounds(row, col)) return false
        return baseGetBit(row, col)
    }

    // Проходимость с учётом пользовательских правок
    fun isPassable(row: Int, col: Int): Boolean {
        if (!isInBounds(row, col)) return false
        return modifiedCells.getOrElse(row * COLS + col) { baseGetBit(row, col) }
    }

    // Клетка изменена относительно базы
    fun isModified(row: Int, col: Int): Boolean {
        if (!isInBounds(row, col)) return false
        val key      = row * COLS + col
        val override = modifiedCells[key] ?: return false
        return override != baseGetBit(row, col)
    }

    // Добавляет пользовательское препятствие
    fun setObstacle(row: Int, col: Int) {
        if (isInBounds(row, col)) modifiedCells[row * COLS + col] = false
    }

    // Пробивает пользовательский проход
    fun setPassage(row: Int, col: Int) {
        if (isInBounds(row, col)) modifiedCells[row * COLS + col] = true
    }

    // Количество реально изменённых клеток
    fun getModifiedCount(): Int = modifiedCells.count { (key, passable) ->
        passable != baseGetBit(key / COLS, key % COLS)
    }

    // Сбрасывает все пользовательские правки
    fun resetGrid() {
        modifiedCells.clear()
    }

    fun getFoodPoints(): List<FoodPoint> = foodPoints

    // Добавляет точку, если её ещё нет
    fun addFoodPoint(row: Int, col: Int) {
        if (!isInBounds(row, col)) return
        if (foodPoints.none { it.row == row && it.col == col }) foodPoints.add(FoodPoint(row, col))
    }

    fun removeFoodPoint(row: Int, col: Int) {
        foodPoints.removeAll { it.row == row && it.col == col }
    }

    fun getFoodPointAt(row: Int, col: Int): FoodPoint? =
        foodPoints.find { it.row == row && it.col == col }

    // Загружает точку из репозитория с метаданными
    fun addFoodPointFromDb(row: Int, col: Int, name: String, review: String, tags: String, dbId: Long) {
        if (!isInBounds(row, col)) return
        if (foodPoints.none { it.row == row && it.col == col })
            foodPoints.add(FoodPoint(row, col, name, review, tags, dbId = dbId))
    }

    // Обновляет метаданные точки в памяти
    fun updateFoodPointMeta(row: Int, col: Int, name: String, review: String, tags: String) {
        getFoodPointAt(row, col)?.apply { this.name = name; this.review = review; this.tags = tags }
    }

    fun clearFoodPoints() = foodPoints.clear()

    fun getLandmarks(): List<Landmark> = landmarks

    fun addLandmark(row: Int, col: Int) {
        if (!isInBounds(row, col)) return
        if (landmarks.none { it.row == row && it.col == col }) landmarks.add(Landmark(row, col))
    }

    fun removeLandmark(row: Int, col: Int) {
        landmarks.removeAll { it.row == row && it.col == col }
    }

    fun getLandmarkAt(row: Int, col: Int): Landmark? =
        landmarks.find { it.row == row && it.col == col }

    fun clearLandmarks() = landmarks.clear()
}
