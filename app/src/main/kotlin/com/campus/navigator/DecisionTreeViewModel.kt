package com.campus.navigator

import android.content.Context
import kotlin.math.sqrt

/**
 * Хранит состояние модуля "Дерево решений": обученное дерево, датасет,
 * методы загрузки/парсинга CSV, генерации HTML-визуализации и предсказания.
 *
 * Персистентность: датасет сохраняется в SharedPreferences (ключ "decision_tree_dataset").
 * При создании автоматически загружает сохранённый датасет и переобучает дерево.
 */
class DecisionTreeViewModel(private val context: Context) {

    companion object {
        private const val PREFS      = "decision_tree"
        private const val KEY_DATA   = "decision_tree_dataset"

        /** Признаки модели (без целевого). */
        val FEATURES = listOf(
            "location", "budget", "time_available",
            "food_type", "queue_tolerance", "weather"
        )

        /** Значения признаков по умолчанию (показываются до загрузки датасета). */
        val DEFAULT_VALUES = mapOf(
            "location"        to listOf("main_building", "second_building", "bus_stop", "campus_center"),
            "budget"          to listOf("low", "medium", "high"),
            "time_available"  to listOf("very_short", "short", "medium"),
            "food_type"       to listOf("coffee", "pancakes", "full_meal", "snack"),
            "queue_tolerance" to listOf("low", "medium", "high"),
            "weather"         to listOf("good", "bad")
        )
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Обученное дерево (null пока не загружен датасет). */
    var tree: DecisionTreeNode? = null
        private set

    /** Обучающая выборка в памяти. */
    var samples: List<Map<String, String>> = emptyList()
        private set

    init { loadSaved() }

    // ── Загрузка и обучение ───────────────────────────────────────────────────

    /**
     * Парсит CSV, обучает ID3, сохраняет датасет.
     * @return Строка-статус для отображения пользователю.
     */
    fun loadAndTrain(csvText: String): String {
        val parsed = parseCSV(csvText)
        if (parsed.isEmpty()) return "Ошибка: не удалось прочитать данные. Проверьте формат CSV."

        samples = parsed
        prefs.edit().putString(KEY_DATA, csvText).apply()
        tree = ID3Algorithm.buildTree(samples, FEATURES)

        val d = ID3Algorithm.depth(tree!!)
        val classes = samples.map { it[ID3Algorithm.TARGET] }.distinct().size
        return "Загружено ${samples.size} строк, $classes заведений. Глубина дерева: $d."
    }

    /** Возвращает сохранённый CSV (или образец из assets). */
    fun getSavedCsv(): String =
        prefs.getString(KEY_DATA, null) ?: loadSampleCsv()

    /** Уникальные значения признака из обучающей выборки (или дефолтные). */
    fun valuesFor(feature: String): List<String> {
        val fromData = if (samples.isNotEmpty()) ID3Algorithm.featureValues(samples, feature) else emptyList()
        return fromData.ifEmpty { DEFAULT_VALUES[feature] ?: emptyList() }
    }

    // ── Предсказание ─────────────────────────────────────────────────────────

    /**
     * Прогоняет входные признаки по дереву.
     * @return Пара (заведение, путь по узлам) или null если дерево не обучено.
     */
    fun predict(input: Map<String, String>): Pair<String, List<String>>? =
        tree?.let { ID3Algorithm.predict(it, input) }

    // ── Динамический расчёт location ─────────────────────────────────────────

    /**
     * Вычисляет категорию location для заданной клетки карты
     * на основе центроидов K-means кластеров.
     *
     * Находит ближайший кластер к позиции студента, затем определяет
     * среднее расстояние до кластеров заведений:
     *   close  = ближайший кластер с расстоянием ≤ 150 клеток
     *   medium = 150–300 клеток
     *   far    = > 300 клеток
     *
     * Если кластеры не рассчитаны — возвращает null.
     */
    fun calcLocation(studentRow: Int, studentCol: Int, centroids: List<Pair<Float, Float>>): String? {
        if (centroids.isEmpty()) return null
        val nearest = centroids.minByOrNull { (r, c) ->
            euclidean(studentRow.toFloat(), studentCol.toFloat(), r, c)
        } ?: return null
        val dist = euclidean(studentRow.toFloat(), studentCol.toFloat(), nearest.first, nearest.second)
        return when {
            dist <= 150f -> "close"
            dist <= 300f -> "medium"
            else         -> "far"
        }
    }

    private fun euclidean(r1: Float, c1: Float, r2: Float, c2: Float): Float {
        val dr = r1 - r2; val dc = c1 - c2
        return sqrt(dr * dr + dc * dc)
    }

    // ── HTML-визуализация ─────────────────────────────────────────────────────

    /** Генерирует HTML для WebView с визуализацией дерева. */
    fun generateTreeHtml(): String {
        val root = tree
            ?: return wrapHtml("<p style='color:#666;text-align:center;padding:40px 16px;font-size:14px'>Дерево не обучено.<br>Загрузите CSV на вкладке «Обучение».</p>")

        val content = "<ul class='root'>${nodeToHtml(root)}</ul>"
        return wrapHtml(content)
    }

    private fun nodeToHtml(node: DecisionTreeNode): String = when (node) {
        is DecisionTreeNode.Leaf ->
            "<li><span class='leaf'>${escHtml(node.label)}<br><small>${node.count} пр.</small></span></li>"

        is DecisionTreeNode.Branch -> {
            val childrenHtml = node.children.entries.joinToString("") { (value, child) ->
                "<li>" +
                "<span class='edge'>${escHtml(value)}</span>" +
                "<ul>${nodeToHtml(child)}</ul>" +
                "</li>"
            }
            "<li><span class='branch'>${escHtml(node.feature)}</span><ul>$childrenHtml</ul></li>"
        }
    }

    private fun escHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun wrapHtml(content: String) = """
<!DOCTYPE html><html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font:12px Arial,sans-serif;background:#F2F4F7;padding:12px;overflow-x:auto}
ul{padding-left:0;list-style:none}
ul.root{display:inline-block;white-space:nowrap}
li{position:relative;padding:6px 0 6px 24px}
li::before{content:'';position:absolute;left:0;top:14px;width:20px;height:2px;background:#0057A8}
ul ul{padding-left:0;border-left:2px solid #0057A8;margin-left:12px}
ul ul li{padding-left:20px}
ul ul li::before{width:16px}
.branch{display:inline-block;background:#0057A8;color:#fff;border-radius:6px;
        padding:5px 10px;font-weight:bold;font-size:11px;cursor:default}
.leaf{display:inline-block;background:#003D7A;color:#fff;border-radius:12px;
      padding:5px 10px;font-size:11px;text-align:center;cursor:default}
.leaf small{display:block;font-size:9px;opacity:.8;margin-top:1px}
.edge{display:inline-block;font-size:10px;color:#0057A8;font-style:italic;
      margin-bottom:2px;padding:0 4px}
</style></head><body>
$content
</body></html>""".trimIndent()

    // ── Внутренние вспомогательные методы ────────────────────────────────────

    private fun loadSaved() {
        val csv = prefs.getString(KEY_DATA, null) ?: return
        val parsed = parseCSV(csv)
        if (parsed.isNotEmpty()) {
            samples = parsed
            tree = ID3Algorithm.buildTree(samples, FEATURES)
        }
    }

    private fun loadSampleCsv(): String = try {
        context.assets.open("decision_tree_sample.csv").bufferedReader().readText()
    } catch (_: Exception) { "" }

    private fun parseCSV(text: String): List<Map<String, String>> {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val headers = lines[0].split(",").map { it.trim().lowercase() }
        if (!headers.contains(ID3Algorithm.TARGET)) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val values = line.split(",").map { it.trim() }
            if (values.size == headers.size) headers.zip(values).toMap() else null
        }
    }
}
