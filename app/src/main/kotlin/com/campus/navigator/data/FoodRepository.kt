package com.campus.navigator.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранилище заведений и блюд на основе SharedPreferences + JSON.
 * Никаких внешних библиотек — только стандартный Android SDK.
 *
 * Все данные держатся в памяти; SharedPreferences — дисковый бэкап.
 * Чтение быстрое (in-memory), запись асинхронна через apply().
 */
class FoodRepository(context: Context) {

    private val prefs = context.getSharedPreferences("food_data", Context.MODE_PRIVATE)

    private val foodPoints = mutableListOf<FoodPointData>()
    private val dishes     = mutableListOf<DishData>()
    /** Пары (foodPointId, dishId) — связи многие-ко-многим. */
    private val links      = mutableListOf<Pair<Long, Long>>()

    private var nextFpId   = 1L
    private var nextDishId = 1L

    init { load() }

    // ── Загрузка из SharedPreferences ────────────────────────────────────────

    private fun load() {
        // Заведения
        val fpArr = JSONArray(prefs.getString("food_points", "[]") ?: "[]")
        for (i in 0 until fpArr.length()) {
            val o = fpArr.getJSONObject(i)
            foodPoints.add(FoodPointData(
                id      = o.getLong("id"),
                row     = o.getInt("row"),
                col     = o.getInt("col"),
                name    = o.getString("name"),
                review  = o.getString("review"),
                tags    = o.getString("tags"),
                isOnMap = o.getBoolean("isOnMap")
            ))
        }
        nextFpId = (foodPoints.maxOfOrNull { it.id } ?: 0L) + 1L

        // Блюда
        val dArr = JSONArray(prefs.getString("dishes", "[]") ?: "[]")
        for (i in 0 until dArr.length()) {
            val o = dArr.getJSONObject(i)
            dishes.add(DishData(o.getLong("id"), o.getString("name")))
        }
        nextDishId = (dishes.maxOfOrNull { it.id } ?: 0L) + 1L

        // Связи
        val lArr = JSONArray(prefs.getString("links", "[]") ?: "[]")
        for (i in 0 until lArr.length()) {
            val o = lArr.getJSONObject(i)
            links.add(Pair(o.getLong("fpId"), o.getLong("dishId")))
        }
    }

    // ── Сохранение в SharedPreferences ───────────────────────────────────────

    private fun saveFoodPoints() {
        val arr = JSONArray()
        foodPoints.forEach { fp ->
            arr.put(JSONObject().apply {
                put("id", fp.id);   put("row", fp.row); put("col", fp.col)
                put("name", fp.name); put("review", fp.review); put("tags", fp.tags)
                put("isOnMap", fp.isOnMap)
            })
        }
        prefs.edit().putString("food_points", arr.toString()).apply()
    }

    private fun saveDishes() {
        val arr = JSONArray()
        dishes.forEach { d -> arr.put(JSONObject().apply { put("id", d.id); put("name", d.name) }) }
        prefs.edit().putString("dishes", arr.toString()).apply()
    }

    private fun saveLinks() {
        val arr = JSONArray()
        links.forEach { (fpId, dId) ->
            arr.put(JSONObject().apply { put("fpId", fpId); put("dishId", dId) })
        }
        prefs.edit().putString("links", arr.toString()).apply()
    }

    // ── Заведения ─────────────────────────────────────────────────────────────

    fun getAllOnMap(): List<FoodPointData>  = foodPoints.filter { it.isOnMap }
    fun getAllFoodPoints(): List<FoodPointData> = foodPoints.toList()
    fun getByCoords(row: Int, col: Int): FoodPointData? = foodPoints.find { it.row == row && it.col == col }

    /** Возвращает ID нового заведения. */
    fun insertFoodPoint(
        row: Int, col: Int,
        name: String = "", review: String = "", tags: String = "",
        isOnMap: Boolean = true
    ): Long {
        val id = nextFpId++
        foodPoints.add(FoodPointData(id, row, col, name, review, tags, isOnMap))
        saveFoodPoints()
        return id
    }

    fun updateFoodPoint(fp: FoodPointData) {
        val idx = foodPoints.indexOfFirst { it.id == fp.id }
        if (idx >= 0) { foodPoints[idx] = fp; saveFoodPoints() }
    }

    fun deleteFoodPointById(id: Long) {
        foodPoints.removeAll { it.id == id }
        links.removeAll { it.first == id }
        saveFoodPoints(); saveLinks()
    }

    // ── Связи блюд ────────────────────────────────────────────────────────────

    fun insertLink(foodPointId: Long, dishId: Long) {
        if (links.none { it.first == foodPointId && it.second == dishId }) {
            links.add(Pair(foodPointId, dishId)); saveLinks()
        }
    }

    fun clearLinks(fpId: Long) {
        links.removeAll { it.first == fpId }; saveLinks()
    }

    fun getLinkedDishes(fpId: Long): List<DishData> {
        val ids = links.filter { it.first == fpId }.map { it.second }.toSet()
        return dishes.filter { it.id in ids }.sortedBy { it.name }
    }

    fun findOnMapByDishIds(dishIds: List<Long>): List<FoodPointData> {
        val fpIds = links.filter { it.second in dishIds }.map { it.first }.toSet()
        return foodPoints.filter { it.id in fpIds && it.isOnMap }
    }

    // ── Блюда ─────────────────────────────────────────────────────────────────

    fun getAllDishes(): List<DishData> = dishes.sortedBy { it.name }

    /** Возвращает ID нового блюда. */
    fun insertDish(name: String): Long {
        val id = nextDishId++
        dishes.add(DishData(id, name)); saveDishes()
        return id
    }

    fun updateDish(dish: DishData) {
        val idx = dishes.indexOfFirst { it.id == dish.id }
        if (idx >= 0) { dishes[idx] = dish; saveDishes() }
    }

    fun deleteDishById(id: Long) {
        dishes.removeAll { it.id == id }
        links.removeAll { it.second == id }
        saveDishes(); saveLinks()
    }

    // ── Комбинированные запросы ───────────────────────────────────────────────

    fun getAllWithDishes(): List<FoodPointWithDishesData> =
        foodPoints.sortedBy { it.name }.map { fp -> FoodPointWithDishesData(fp, getLinkedDishes(fp.id)) }
}
