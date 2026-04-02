package com.campus.navigator

import  android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import com.campus.navigator.algorithm.PathFinderww
import com.campus.navigator.data.MapData
import com.campus.navigator.view.MapView

/**
 * мейн Activity
 */
class MainActivity : Activity() {

    private lateinit var mapView: MapView
    private lateinit var statusText: TextView

    private lateinit var btnToggleImpassable: Button
    private lateinit var btnClear: Button
    private lateinit var btnFindPath: Button
    private lateinit var btnToggleGrid: Button

    private lateinit var btnAddObstacle: Button
    private lateinit var btnAddPassage: Button
    private lateinit var btnResetGrid: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        updateStatus()
    }

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        statusText = findViewById(R.id.statusText)

        btnToggleImpassable = findViewById(R.id.btnToggleImpassable)
        btnClear = findViewById(R.id.btnClear)
        btnFindPath = findViewById(R.id.btnFindPath)
        btnToggleGrid = findViewById(R.id.btnToggleGrid)

        btnAddObstacle = findViewById(R.id.btnAddObstacle)
        btnAddPassage = findViewById(R.id.btnAddPassage)
        btnResetGrid = findViewById(R.id.btnResetGrid)
    }

    private fun setupListeners() {
        btnToggleImpassable.setOnClickListener {
            mapView.showImpassable = !mapView.showImpassable
            btnToggleImpassable.text = if (mapView.showImpassable)
                "Скрыть препятствия" else "Показать препятствия"
        }

        btnToggleGrid.setOnClickListener {
            mapView.showGrid = !mapView.showGrid
            btnToggleGrid.text = if (mapView.showGrid)
                "Скрыть сетку" else "Показать сетку"
        }

        btnClear.setOnClickListener {
            mapView.clearSelection()
            updateStatus()
        }

        btnFindPath.setOnClickListener {
            val start = mapView.startCell
            val end = mapView.endCell
            if (start == null || end == null) {
                Toast.makeText(this, "Выберите начальную и конечную точки", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Вызов алгоритма поиска пути
            val result = PathFinderww.findPath(start, end)
            mapView.currentPath = result.path
            mapView.visitedCells = result.visited

            if (result.found) {
                Toast.makeText(this, "Путь найден!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Путь не найден. Проверьте, нет ли препятствий на старте/финише.", Toast.LENGTH_LONG).show()
            }
        }

        btnAddObstacle.setOnClickListener {
            if (mapView.interactionMode == MapView.InteractionMode.ADD_OBSTACLE) {
                mapView.interactionMode = MapView.InteractionMode.SELECT_POINT
            } else {
                mapView.interactionMode = MapView.InteractionMode.ADD_OBSTACLE
            }
            updateModeButtons()
            updateStatus()
        }

        btnAddPassage.setOnClickListener {
            if (mapView.interactionMode == MapView.InteractionMode.ADD_PASSAGE) {
                mapView.interactionMode = MapView.InteractionMode.SELECT_POINT
            } else {
                mapView.interactionMode = MapView.InteractionMode.ADD_PASSAGE
            }
            updateModeButtons()
            updateStatus()
        }

        btnResetGrid.setOnClickListener {
            val modifiedCount = MapData.getModifiedCount()
            if (modifiedCount == 0) {
                Toast.makeText(this, "Нет изменений для сброса", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            MapData.resetGrid()
            mapView.invalidate()
            Toast.makeText(
                this,
                "Матрица сброшена ($modifiedCount изменений отменено)",
                Toast.LENGTH_SHORT
            ).show()
            updateStatus()
        }

        mapView.onCellSelectedListener = { cell, isStart ->
            val passable = MapData.isPassable(cell.row, cell.col)
            val typeStr = if (passable) "проход" else "препятствие"
            val roleStr = if (isStart) "Старт" else "Финиш"

            statusText.text = "$roleStr: [${cell.row}, ${cell.col}] ($typeStr)"

            if (!passable) {
                Toast.makeText(
                    this,
                    "Внимание: выбрана непроходимая клетка!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        mapView.onCellEditedListener = { cell, nowPassable ->
            val action = if (nowPassable) "Проход добавлен" else "Препятствие добавлено"
            val modified = MapData.getModifiedCount()
            statusText.text = "$action: [${cell.row}, ${cell.col}] | Изменено: $modified"
        }
    }

    private fun updateModeButtons() {
        when (mapView.interactionMode) {
            MapView.InteractionMode.SELECT_POINT -> {
                btnAddObstacle.text = "+ Препятствие"
                btnAddPassage.text = "+ Проход"
            }
            MapView.InteractionMode.ADD_OBSTACLE -> {
                btnAddObstacle.text = "● ПРЕПЯТСТВИЕ"
                btnAddPassage.text = "+ Проход"
            }
            MapView.InteractionMode.ADD_PASSAGE -> {
                btnAddObstacle.text = "+ Препятствие"
                btnAddPassage.text = "● ПРОХОД"
            }
        }
    }

    private fun updateStatus() {
        when (mapView.interactionMode) {
            MapView.InteractionMode.ADD_OBSTACLE -> {
                val modified = MapData.getModifiedCount()
                statusText.text = "Режим: ДОБАВИТЬ ПРЕПЯТСТВИЕ | Изменено: $modified"
                return
            }
            MapView.InteractionMode.ADD_PASSAGE -> {
                val modified = MapData.getModifiedCount()
                statusText.text = "Режим: ДОБАВИТЬ ПРОХОД | Изменено: $modified"
                return
            }
            MapView.InteractionMode.SELECT_POINT -> {
            }
        }

        val start = mapView.startCell
        val end = mapView.endCell
        val sb = StringBuilder("Режим: выбор точек | ")

        if (start != null) {
            sb.append("Старт: [${start.row}, ${start.col}]")
        }
        if (end != null) {
            if (start != null) sb.append("  →  ")
            sb.append("Финиш: [${end.row}, ${end.col}]")
        }
        if (start == null && end == null) {
            sb.append("Нажмите на карту")
        }

        statusText.text = sb.toString()
    }
}
