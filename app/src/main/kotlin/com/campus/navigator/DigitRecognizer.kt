package com.campus.navigator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DigitRecognizer {

    var gridSize: Int = 5
        private set

    private val layers = mutableListOf<Pair<Array<FloatArray>, FloatArray>>()

    val isReady: Boolean get() = layers.isNotEmpty()

    fun init(context: Context) {
        if (isReady) return
        try {
            val json = context.assets.open("digit_weights.json")
                .bufferedReader().use { it.readText() }
            val obj = JSONObject(json)

            gridSize = obj.optInt("grid", 5)
            layers.clear()

            val jsonLayers = obj.getJSONArray("layers")
            for (i in 0 until jsonLayers.length()) {
                val layer = jsonLayers.getJSONObject(i)
                val w = parseMatrix(layer.getJSONArray("w"))
                val b = parseVector(layer.getJSONArray("b"))
                layers.add(Pair(w, b))
            }
        } catch (e: Exception) {
            layers.clear()
        }
    }

    fun predict(pixels: FloatArray): Pair<Int, Float>? {
        if (!isReady) return null

        var activation = pixels.copyOf()

        // Прогоняем через все слои кроме последнего с ReLU
        for (i in 0 until layers.size - 1) {
            activation = denseRelu(activation, layers[i].first, layers[i].second)
        }

        // Последний слой — Softmax
        val (wLast, bLast) = layers.last()
        val logits = dense(activation, wLast, bLast)
        val probs  = softmax(logits)

        val predicted = probs.indices.maxByOrNull { probs[it] }!!
        return Pair(predicted, probs[predicted])
    }

    // ── Математика ────────────────────────────────────────────────────────────

    private fun dense(x: FloatArray, w: Array<FloatArray>, b: FloatArray): FloatArray {
        val out = FloatArray(b.size)
        for (j in b.indices) {
            var sum = b[j]
            for (i in x.indices) sum += x[i] * w[i][j]
            out[j] = sum
        }
        return out
    }

    private fun denseRelu(x: FloatArray, w: Array<FloatArray>, b: FloatArray): FloatArray {
        val out = dense(x, w, b)
        for (i in out.indices) if (out[i] < 0f) out[i] = 0f
        return out
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val exp = FloatArray(logits.size) { Math.exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exp.sum()
        return FloatArray(exp.size) { exp[it] / sum }
    }

    // ── Парсинг JSON ──────────────────────────────────────────────────────────

    private fun parseMatrix(arr: JSONArray): Array<FloatArray> =
        Array(arr.length()) { i ->
            val row = arr.getJSONArray(i)
            FloatArray(row.length()) { j -> row.getDouble(j).toFloat() }
        }

    private fun parseVector(arr: JSONArray): FloatArray =
        FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
}
