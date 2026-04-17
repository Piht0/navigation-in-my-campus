package com.campus.navigator

import kotlin.math.log2

/**
 * Алгоритм построения дерева решений ID3 (Iterative Dichotomiser 3).
 *
 * Работает с набором обучающих примеров в виде Map<String, String>,
 * где каждый ключ — имя признака, значение — его категориальное значение.
 * Целевой признак фиксирован константой [TARGET].
 *
 * Критерий разбивки — Information Gain (прирост информации):
 *   IG(S, A) = H(S) − Σ |Sv|/|S| · H(Sv)
 * где H — энтропия Шеннона по целевому признаку.
 */
object ID3Algorithm {

    /** Имя целевого признака в обучающей выборке. */
    const val TARGET = "recommended_place"

    // ── Построение дерева ────────────────────────────────────────────────────

    /**
     * Рекурсивно строит дерево решений.
     *
     * @param samples  Обучающие примеры (каждый — Map признак→значение).
     * @param features Список признаков для разбивки (без целевого).
     * @return Корень построенного дерева.
     */
    fun buildTree(
        samples: List<Map<String, String>>,
        features: List<String>
    ): DecisionTreeNode {
        val labels = samples.map { it[TARGET] ?: "" }

        // Все примеры одного класса → лист
        if (labels.distinct().size == 1) {
            return DecisionTreeNode.Leaf(labels[0], labels.size)
        }

        // Нет признаков для разбивки → лист с большинством
        if (features.isEmpty() || samples.isEmpty()) {
            return DecisionTreeNode.Leaf(majority(labels), samples.size)
        }

        // Выбираем признак с максимальным IG
        val bestFeature = features.maxByOrNull { infoGain(samples, it) } ?: features[0]
        val values = samples.map { it[bestFeature] ?: "" }.distinct().sorted()
        val remaining = features - bestFeature

        val children = LinkedHashMap<String, DecisionTreeNode>()
        for (value in values) {
            val subset = samples.filter { it[bestFeature] == value }
            children[value] = if (subset.isEmpty()) {
                DecisionTreeNode.Leaf(majority(labels), 0)
            } else {
                buildTree(subset, remaining)
            }
        }

        return DecisionTreeNode.Branch(bestFeature, children)
    }

    // ── Предсказание ─────────────────────────────────────────────────────────

    /**
     * Прогоняет входной пример по дереву.
     *
     * @param node  Корень дерева.
     * @param input Признаки примера (признак → значение).
     * @return Пара: (предсказанное заведение, путь по узлам).
     *         Путь — список строк вида "признак = значение".
     */
    fun predict(
        node: DecisionTreeNode,
        input: Map<String, String>
    ): Pair<String, List<String>> {
        val path = mutableListOf<String>()

        fun traverse(n: DecisionTreeNode): String = when (n) {
            is DecisionTreeNode.Leaf -> n.label

            is DecisionTreeNode.Branch -> {
                val value = input[n.feature] ?: ""
                path.add("${n.feature} = $value")
                // Точное совпадение; если нет — берём первую ветку как fallback
                val child = n.children[value] ?: n.children.values.firstOrNull()
                if (child == null) n.feature else traverse(child)
            }
        }

        val result = traverse(node)
        return Pair(result, path.toList())
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    /** Максимальная глубина дерева (корень — глубина 0). */
    fun depth(node: DecisionTreeNode): Int = when (node) {
        is DecisionTreeNode.Leaf   -> 0
        is DecisionTreeNode.Branch ->
            1 + (node.children.values.maxOfOrNull { depth(it) } ?: 0)
    }

    /** Все уникальные значения признака в датасете, отсортированные. */
    fun featureValues(samples: List<Map<String, String>>, feature: String): List<String> =
        samples.map { it[feature] ?: "" }.filter { it.isNotBlank() }.distinct().sorted()

    /** Прирост информации для признака [feature]. */
    fun infoGain(samples: List<Map<String, String>>, feature: String): Double {
        if (samples.isEmpty()) return 0.0
        val total = samples.size.toDouble()
        val weighted = samples.groupBy { it[feature] }
            .values.sumOf { subset -> (subset.size / total) * entropy(subset) }
        return entropy(samples) - weighted
    }

    /** Энтропия Шеннона выборки по целевому признаку. */
    private fun entropy(samples: List<Map<String, String>>): Double {
        if (samples.isEmpty()) return 0.0
        val total = samples.size.toDouble()
        return samples.groupBy { it[TARGET] }.values.sumOf { group ->
            val p = group.size / total
            if (p <= 0.0) 0.0 else -p * log2(p)
        }
    }

    /** Класс с максимальным числом вхождений в списке меток. */
    private fun majority(labels: List<String>): String =
        labels.groupBy { it }.maxByOrNull { it.value.size }?.key ?: "unknown"
}
