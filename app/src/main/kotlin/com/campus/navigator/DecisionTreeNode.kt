package com.campus.navigator

/**
 * Узел дерева решений ID3.
 *
 * [Branch] — внутренний узел: содержит признак для разбивки и дочерние
 *            ветви по каждому значению этого признака.
 * [Leaf]   — лист: хранит предсказанный класс (название заведения) и число
 *            примеров из обучающей выборки, попавших в этот лист.
 */
sealed class DecisionTreeNode {

    /**
     * Внутренний узел дерева.
     * @param feature  Имя признака (например, "budget").
     * @param children Отображение: значение признака → дочерний узел.
     *                 LinkedHashMap сохраняет порядок вставки для HTML-визуализации.
     */
    data class Branch(
        val feature: String,
        val children: LinkedHashMap<String, DecisionTreeNode>
    ) : DecisionTreeNode()

    /**
     * Лист дерева.
     * @param label Предсказанный класс (recommended_place).
     * @param count Число обучающих примеров в этом листе.
     */
    data class Leaf(
        val label: String,
        val count: Int
    ) : DecisionTreeNode()
}
