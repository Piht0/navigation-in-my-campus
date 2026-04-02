package com.campus.navigator.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.campus.navigator.R
import com.campus.navigator.algorithm.PathFinderww
import com.campus.navigator.data.MapData

/**
 * Кастомная View для отображения карты кампуса с сеткой проходимости.
 */
class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ===================== РЕЖИМЫ ВЗАИМОДЕЙСТВИЯ =====================

    enum class InteractionMode {
        SELECT_POINT,
        ADD_OBSTACLE,
        ADD_PASSAGE
    }

    // ===================== ДАННЫЕ КАРТЫ =====================

    private var mapBitmap: Bitmap? = null

    // Размер сетки
    private val gridRows = MapData.ROWS
    private val gridCols = MapData.COLS

    // ===================== СОСТОЯНИЕ =====================

    var interactionMode = InteractionMode.SELECT_POINT
        set(value) {
            field = value
            onModeChangedListener?.invoke(value)
        }

    var startCell: PathFinderww.Cell? = null
        private set
    var endCell: PathFinderww.Cell? = null
        private set

    private var selectingStart = true

    var showImpassable = false
        set(value) {
            field = value
            invalidate()
        }

    var showGrid = false
        set(value) {
            field = value
            invalidate()
        }

    var currentPath: List<PathFinderww.Cell> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var visitedCells: Set<PathFinderww.Cell> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    var onCellSelectedListener: ((cell: PathFinderww.Cell, isStart: Boolean) -> Unit)? = null
    var onCellEditedListener: ((cell: PathFinderww.Cell, nowPassable: Boolean) -> Unit)? = null
    var onModeChangedListener: ((mode: InteractionMode) -> Unit)? = null

    // ===================== ТРАНСФОРМАЦИЯ =====================

    private var scaleFactor = 1.0f
    private val minScale = 0.5f
    private val maxScale = 10.0f

    private var translateX = 0f
    private var translateY = 0f

    private val transformMatrix = Matrix()
    private val inverseMatrix = Matrix()

    // ===================== СТИЛИ =====================

    private val impassablePaint = Paint().apply {
        color = Color.argb(100, 255, 0, 0)
        style = Paint.Style.FILL
    }

    private val startPaint = Paint().apply {
        color = Color.argb(200, 0, 200, 0)
        style = Paint.Style.FILL
    }

    private val endPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 255)
        style = Paint.Style.FILL
    }

    // Изменено на желтый цвет для маршрута
    private val pathPaint = Paint().apply {
        color = Color.argb(220, 255, 255, 0) // Желтый
        style = Paint.Style.FILL
    }

    private val visitedPaint = Paint().apply {
        color = Color.argb(60, 100, 100, 255)
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.argb(30, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
    }

    private val markerBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val userObstaclePaint = Paint().apply {
        color = Color.argb(160, 180, 0, 180)
        style = Paint.Style.FILL
    }

    private val userPassagePaint = Paint().apply {
        color = Color.argb(160, 0, 200, 100)
        style = Paint.Style.FILL
    }

    // ===================== ЖЕСТЫ =====================

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldScale = scaleFactor
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(minScale, maxScale)

                val focusX = detector.focusX
                val focusY = detector.focusY
                val scaleChange = scaleFactor / oldScale
                translateX = focusX - scaleChange * (focusX - translateX)
                translateY = focusY - scaleChange * (focusY - translateY)

                invalidate()
                return true
            }
        }
    )

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var pointerCount = 0

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1.5f) {
                    scaleFactor = 1.0f
                    translateX = 0f
                    translateY = 0f
                } else {
                    scaleFactor = 3.0f
                    translateX = width / 2f - e.x * 3f
                    translateY = height / 2f - e.y * 3f
                }
                invalidate()
                return true
            }
        }
    )

    init {
        loadMapBitmap()
    }

    private fun loadMapBitmap() {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        mapBitmap = BitmapFactory.decodeResource(resources, R.drawable.campus_map, options)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = mapBitmap ?: return

        val cellWidth = bmp.width.toFloat() / gridCols
        val cellHeight = bmp.height.toFloat() / gridRows

        canvas.save()
        updateTransformMatrix()
        canvas.concat(transformMatrix)

        canvas.drawBitmap(bmp, 0f, 0f, null)

        if (showImpassable) {
            drawImpassableCells(canvas, cellWidth, cellHeight)
        }

        drawUserModifiedCells(canvas, cellWidth, cellHeight)

        if (visitedCells.isNotEmpty()) {
            drawCellSet(canvas, visitedCells, visitedPaint, cellWidth, cellHeight)
        }

        if (currentPath.isNotEmpty()) {
            drawCellSet(canvas, currentPath.toSet(), pathPaint, cellWidth, cellHeight)
        }

        if (showGrid) {
            drawGrid(canvas, bmp.width.toFloat(), bmp.height.toFloat(), cellWidth, cellHeight)
        }

        drawMarker(canvas, startCell, startPaint, cellWidth, cellHeight)
        drawMarker(canvas, endCell, endPaint, cellWidth, cellHeight)

        canvas.restore()
    }

    private fun updateTransformMatrix() {
        transformMatrix.reset()
        transformMatrix.postScale(scaleFactor, scaleFactor)
        transformMatrix.postTranslate(translateX, translateY)
        transformMatrix.invert(inverseMatrix)
    }

    private fun drawImpassableCells(canvas: Canvas, cellW: Float, cellH: Float) {
        val visibleRect = getVisibleGridRect(cellW, cellH)
        for (r in visibleRect.top..visibleRect.bottom) {
            for (c in visibleRect.left..visibleRect.right) {
                if (!MapData.isPassable(r, c)) {
                    canvas.drawRect(c * cellW, r * cellH, (c + 1) * cellW, (r + 1) * cellH, impassablePaint)
                }
            }
        }
    }

    private fun drawUserModifiedCells(canvas: Canvas, cellW: Float, cellH: Float) {
        val visibleRect = getVisibleGridRect(cellW, cellH)
        for (r in visibleRect.top..visibleRect.bottom) {
            for (c in visibleRect.left..visibleRect.right) {
                if (MapData.isModified(r, c)) {
                    val paint = if (MapData.isPassable(r, c)) userPassagePaint else userObstaclePaint
                    canvas.drawRect(c * cellW, r * cellH, (c + 1) * cellW, (r + 1) * cellH, paint)
                }
            }
        }
    }

    private fun drawCellSet(canvas: Canvas, cells: Set<PathFinderww.Cell>, paint: Paint, cellW: Float, cellH: Float) {
        for (cell in cells) {
            canvas.drawRect(cell.col * cellW, cell.row * cellH, (cell.col + 1) * cellW, (cell.row + 1) * cellH, paint)
        }
    }

    private fun drawGrid(canvas: Canvas, mapW: Float, mapH: Float, cellW: Float, cellH: Float) {
        for (r in 0..gridRows) {
            val y = r * cellH
            canvas.drawLine(0f, y, mapW, y, gridPaint)
        }
        for (c in 0..gridCols) {
            val x = c * cellW
            canvas.drawLine(x, 0f, x, mapH, gridPaint)
        }
    }

    private fun drawMarker(canvas: Canvas, cell: PathFinderww.Cell?, paint: Paint, cellW: Float, cellH: Float) {
        cell ?: return
        val cx = cell.col * cellW + cellW / 2
        val cy = cell.row * cellH + cellH / 2
        val radius = Math.min(cellW, cellH) * 0.6f
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawCircle(cx, cy, radius, markerBorderPaint)
    }

    private fun getVisibleGridRect(cellW: Float, cellH: Float): Rect {
        val pts = floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        inverseMatrix.mapPoints(pts)
        val left = ((pts[0] / cellW).toInt() - 1).coerceIn(0, gridCols - 1)
        val top = ((pts[1] / cellH).toInt() - 1).coerceIn(0, gridRows - 1)
        val right = ((pts[2] / cellW).toInt() + 1).coerceIn(0, gridCols - 1)
        val bottom = ((pts[3] / cellH).toInt() + 1).coerceIn(0, gridRows - 1)
        return Rect(left, top, right, bottom)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                pointerCount = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> pointerCount = event.pointerCount
            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (isDragging || Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        translateX += dx
                        translateY += dy
                        invalidate()
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> pointerCount = event.pointerCount - 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerCount = 0
        }
        return true
    }

    private fun handleTap(screenX: Float, screenY: Float) {
        val bmp = mapBitmap ?: return
        val cellW = bmp.width.toFloat() / gridCols
        val cellH = bmp.height.toFloat() / gridRows
        val mapCoords = floatArrayOf(screenX, screenY)
        inverseMatrix.mapPoints(mapCoords)
        val col = (mapCoords[0] / cellW).toInt()
        val row = (mapCoords[1] / cellH).toInt()
        if (!MapData.isInBounds(row, col)) return
        val cell = PathFinderww.Cell(row, col)

        when (interactionMode) {
            InteractionMode.SELECT_POINT -> {
                if (selectingStart) {
                    startCell = cell
                    onCellSelectedListener?.invoke(cell, true)
                } else {
                    endCell = cell
                    onCellSelectedListener?.invoke(cell, false)
                }
                selectingStart = !selectingStart
            }
            InteractionMode.ADD_OBSTACLE -> {
                if (MapData.setObstacle(row, col)) onCellEditedListener?.invoke(cell, false)
            }
            InteractionMode.ADD_PASSAGE -> {
                if (MapData.setPassage(row, col)) onCellEditedListener?.invoke(cell, true)
            }
        }
        invalidate()
    }

    fun clearSelection() {
        startCell = null
        endCell = null
        currentPath = emptyList()
        visitedCells = emptySet()
        selectingStart = true
        invalidate()
    }

    fun fitToView() {
        val bmp = mapBitmap ?: return
        if (width == 0 || height == 0) return
        val scaleX = width.toFloat() / bmp.width
        val scaleY = height.toFloat() / bmp.height
        scaleFactor = Math.min(scaleX, scaleY)
        translateX = (width - bmp.width * scaleFactor) / 2f
        translateY = (height - bmp.height * scaleFactor) / 2f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }
}
