package com.campus.navigator

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран распознавания рукописных цифр (0–9).
 *
 * Пользователь рисует цифру на сетке 5×5 пальцем, затем нажимает «Распознать».
 * Нейросеть [DigitRecognizer] (25 → 32 → 10, обученная на MNIST) выдаёт
 * предсказание и уверенность.
 *
 * Веса загружаются из assets/digit_weights.json, который генерирует
 * скрипт train_model_export.py.
 */
class DigitActivity : AppCompatActivity() {

    private lateinit var drawView:      DigitDrawView
    private lateinit var tvResult:      TextView
    private lateinit var tvConfidence:  TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var btnRecognize:  Button
    private lateinit var btnClear:      Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_digit)

        drawView      = findViewById(R.id.digitDrawView)
        tvResult      = findViewById(R.id.tvDigitResult)
        tvConfidence  = findViewById(R.id.tvDigitConfidence)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        btnRecognize  = findViewById(R.id.btnRecognize)
        btnClear      = findViewById(R.id.btnClearDigit)

        // Загружаем веса в фоне, чтобы не тормозить UI
        if (!DigitRecognizer.isReady) {
            tvModelStatus.text = "Загрузка модели..."
            Thread {
                DigitRecognizer.init(this)
                runOnUiThread {
                    tvModelStatus.text = if (DigitRecognizer.isReady)
                        "Модель загружена (5×5 → 32 → 10)"
                    else
                        "Ошибка: файл digit_weights.json не найден в assets"
                }
            }.start()
        } else {
            tvModelStatus.text = "Модель загружена (5×5 → 32 → 10)"
        }

        findViewById<TextView>(R.id.btnCloseDigit).setOnClickListener { finish() }

        btnRecognize.setOnClickListener { recognize() }
        btnClear.setOnClickListener {
            drawView.clear()
            tvResult.text     = "—"
            tvConfidence.text = ""
        }

        // Авто-распознавание при изменении рисунка (опционально)
        drawView.onGridChanged = {
            if (drawView.hasDrawing() && DigitRecognizer.isReady) recognize()
        }
    }

    private fun recognize() {
        if (!DigitRecognizer.isReady) {
            tvResult.text     = "?"
            tvConfidence.text = "Модель не готова"
            return
        }
        if (!drawView.hasDrawing()) {
            tvResult.text     = "—"
            tvConfidence.text = "Нарисуй цифру"
            return
        }

        val pixels = drawView.getPixels()
        val result = DigitRecognizer.predict(pixels)

        if (result == null) {
            tvResult.text     = "?"
            tvConfidence.text = "Ошибка вычисления"
        } else {
            val (digit, confidence) = result
            tvResult.text     = digit.toString()
            tvConfidence.text = "Уверенность: ${"%.1f".format(confidence * 100)}%"
        }
    }
}
