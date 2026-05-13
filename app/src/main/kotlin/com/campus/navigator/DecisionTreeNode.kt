package com.campus.navigator

sealed class DecisionTreeNode {

    data class Branch(
        val feature: String,
        val children: LinkedHashMap<String, DecisionTreeNode>
    ) : DecisionTreeNode()

    data class Leaf(
        val label: String,
        val count: Int
    ) : DecisionTreeNode()
}
