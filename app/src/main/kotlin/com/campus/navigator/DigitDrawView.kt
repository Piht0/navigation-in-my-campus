package com.campus.navigator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Сетка 5×5 для рисования цифры пальцем.
 *
 * Пользователь нажимает/ведёт пальцем — клетки переключаются.
 * Закрашенная клетка = 0.0 (штрих цифры), чистая = 1.0 (фон) —
 * формат соответствует обучающей нормализации train_model_export.py.
 *
 * Колбэк [onGridChanged] вызывается после каждого изменения сетки.
 */
class DigitDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val GRID = 5
    }

    /** true = клетка закрашена (0.0 для сети), false = фон (1.0 для сети). */
    private val cells = Array(GRID) { BooleanArray(GRID) }

    var onGridChanged: (() -> Unit)? = null

    // ── Краски ────────────────────────────────────────────────────────────────

    private val paintFill = Paint().apply {
        color = Color.parseColor("#212121")
        style = Paint.Style.FILL
    }
    private val paintBg = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val paintGrid = Paint().apply {
        color = Color.parseColor("#BBBBBB")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val paintBorder = Paint().apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // ── Размеры клеток ────────────────────────────────────────────────────────

    private var cellW = 0f
    private var cellH = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cellW = w.toFloat() / GRID
        cellH = h.toFloat() / GRID
    }

    // ── Рисование ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        // Фон
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

        // Клетки
        for (r in 0 until GRID) {
            for (c in 0 until GRID) {
                val left   = c * cellW
                val top    = r * cellH
                val right  = left + cellW
                val bottom = top + cellH
                if (cells[r][c]) {
                    canvas.drawRect(left, top, right, bottom, paintFill)
                }
                canvas.drawRect(left, top, right, bottom, paintGrid)
            }
        }

        // Внешняя рамка
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBorder)
    }

    // ── Касания ───────────────────────────────────────────────────────────────

    private var lastToggledRow = -1
    private var lastToggledCol = -1
    private var paintValue     = false   // какое значение ставим при drag

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val col = (event.x / cellW).toInt().coerceIn(0, GRID - 1)
        val row = (event.y / cellH).toInt().coerceIn(0, GRID - 1)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // При первом касании переключаем клетку и запоминаем направление
                paintValue = !cells[row][col]
                cells[row][col] = paintValue
                lastToggledRow = row
                lastToggledCol = col
                onGridChanged?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // При движении закрашиваем/стираем в том же направлении
                if (row != lastToggledRow || col != lastToggledCol) {
                    cells[row][col] = paintValue
                    lastToggledRow = row
                    lastToggledCol = col
                    onGridChanged?.invoke()
                    invalidate()
                }
            }
        }
        return true
    }

    // ── Публичный API ─────────────────────────────────────────────────────────

    /** Сбрасывает все клетки в исходное (пустое) состояние. */
    fun clear() {
        for (r in 0 until GRID) cells[r].fill(false)
        invalidate()
        onGridChanged?.invoke()
    }

    /**
     * Возвращает массив GRID*GRID float для подачи в [DigitRecognizer.predict].
     * Закрашенная клетка → 1.0 (цифра), пустая → 0.0 (фон).
     */
    fun getPixels(): FloatArray {
        val pixels = FloatArray(GRID * GRID)
        for (r in 0 until GRID) {
            for (c in 0 until GRID) {
                pixels[r * GRID + c] = if (cells[r][c]) 1f else 0f
            }
        }
        return pixels
    }

    /** true если хотя бы одна клетка закрашена. */
    fun hasDrawing(): Boolean = cells.any { row -> row.any { it } }
}
