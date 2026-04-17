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

    fun seedIfEmpty() {
        if (foodPoints.isNotEmpty()) return

        val fpJson = """[{"id":1,"row":528,"col":590,"name":"SIBERIAN PUNKAKE","review":"","tags":"","isOnMap":true},{"id":2,"row":525,"col":606,"name":"MINUTE","review":"","tags":"","isOnMap":true},{"id":3,"row":513,"col":602,"name":"FOOD FIRST CAMPUS","review":"","tags":"","isOnMap":true},{"id":4,"row":611,"col":499,"name":"SECOND CAPUMS FOODPLASE","review":"","tags":"","isOnMap":true},{"id":6,"row":592,"col":498,"name":"X0BAKKERY","review":"","tags":"","isOnMap":true},{"id":7,"row":365,"col":561,"name":"CHEES BOR","review":"","tags":"","isOnMap":true},{"id":8,"row":498,"col":558,"name":"starbooks","review":"","tags":"","isOnMap":true},{"id":11,"row":289,"col":253,"name":"abricos","review":"","tags":"","isOnMap":true},{"id":12,"row":91,"col":459,"name":"chinara","review":"","tags":"","isOnMap":true},{"id":13,"row":6,"col":447,"name":"yarche","review":"","tags":"","isOnMap":true},{"id":14,"row":42,"col":783,"name":"tusur stolovaya","review":"","tags":"","isOnMap":true},{"id":15,"row":159,"col":851,"name":"SIBERIAN PUNKAKE","review":"","tags":"","isOnMap":true},{"id":16,"row":95,"col":1041,"name":"driving-coffe","review":"","tags":"","isOnMap":true},{"id":17,"row":84,"col":1116,"name":"33 pinguins","review":"","tags":"","isOnMap":true},{"id":18,"row":67,"col":1044,"name":"icecream","review":"","tags":"","isOnMap":true},{"id":20,"row":216,"col":1088,"name":"sahaurDonar","review":"","tags":"","isOnMap":true},{"id":21,"row":107,"col":1319,"name":"italy","review":"","tags":"","isOnMap":true},{"id":22,"row":455,"col":1475,"name":"torta","review":"","tags":"","isOnMap":true},{"id":24,"row":717,"col":863,"name":"dot","review":"","tags":"","isOnMap":true},{"id":25,"row":553,"col":882,"name":"rostics","review":"","tags":"","isOnMap":true},{"id":26,"row":527,"col":983,"name":"neu tsu stolovaya","review":"","tags":"","isOnMap":true},{"id":27,"row":455,"col":898,"name":"baget omlet","review":"","tags":"","isOnMap":true},{"id":28,"row":318,"col":982,"name":"eternal call","review":"","tags":"","isOnMap":true},{"id":30,"row":308,"col":1029,"name":"yarche","review":"","tags":"","isOnMap":true},{"id":31,"row":517,"col":1028,"name":"our gastronom","review":"","tags":"","isOnMap":true},{"id":32,"row":272,"col":1321,"name":"pyatorochka","review":"","tags":"","isOnMap":true},{"id":33,"row":318,"col":808,"name":"squrell","review":"","tags":"","isOnMap":true},{"id":34,"row":176,"col":712,"name":"quite place","review":"","tags":"","isOnMap":true}]"""

        val dishJson = """[{"id":2,"name":"Solyanka (meat soup)"},{"id":3,"name":"Porridge of the day"},{"id":4,"name":"Herring under beetroot fur coat"},{"id":5,"name":"Olivier salad with turkey"},{"id":6,"name":"Roast beef salad with honey mustard dressing"},{"id":7,"name":"Squid salad with walnut dressing"},{"id":8,"name":"Seared salmon salad with lemon dressing"},{"id":9,"name":"Mimosa salad with muksun fish"},{"id":10,"name":"Vinaigrette with smoked beetroot"},{"id":11,"name":"Vinaigrette with smoked beet and milk mushrooms"},{"id":12,"name":"Vegetable salad with olives and feta cream"},{"id":13,"name":"Varenyky with potato and mushroom"},{"id":14,"name":"Cheburyata (signature mini chebureks)"},{"id":15,"name":"Chicken patties with mashed potato"},{"id":16,"name":"Grilled fish"},{"id":17,"name":"Sweet pancakes"},{"id":18,"name":"Tiramisu"},{"id":19,"name":"Fruit jelly"},{"id":20,"name":"Cream of porcini mushroom soup"},{"id":21,"name":"Cauliflower cream soup with shrimp"},{"id":22,"name":"Omelette with chicken and mushrooms"},{"id":23,"name":"Margherita pizza"},{"id":24,"name":"Spaghetti Carbonara"},{"id":25,"name":"Chicken fritters with mushroom sauce"},{"id":26,"name":"Pork with wasabi mash and Asian demi-glace"},{"id":27,"name":"Braised beef cheeks with oyster mushrooms"},{"id":28,"name":"Pancakes"},{"id":29,"name":"Khinkali with cheese and lamb"},{"id":30,"name":"Crispy eggplant salad"},{"id":31,"name":"Okroshka (cold kvass soup)"},{"id":32,"name":"Shchi (cabbage soup)"},{"id":33,"name":"Pork shashlik"},{"id":34,"name":"Fried potatoes with porcini"},{"id":35,"name":"Beef steak"},{"id":36,"name":"Quesadilla"},{"id":37,"name":"Quiche"},{"id":38,"name":"Cherry pie with almond crumble"},{"id":39,"name":"Syrniki"},{"id":40,"name":"Soup of the day"},{"id":41,"name":"Meat patty with side dish"},{"id":42,"name":"smak"},{"id":43,"name":"sandwich"},{"id":44,"name":"lepeshka"},{"id":45,"name":"hotdog"},{"id":46,"name":"snacks"},{"id":47,"name":"ice cream"},{"id":48,"name":"coffee & tea"},{"id":49,"name":"fried chicken"},{"id":50,"name":"torts"}]"""

        val linksJson = """[{"fpId":8,"dishId":28},{"fpId":8,"dishId":3},{"fpId":8,"dishId":39},{"fpId":8,"dishId":18},{"fpId":8,"dishId":46},{"fpId":17,"dishId":28},{"fpId":17,"dishId":18},{"fpId":17,"dishId":48},{"fpId":17,"dishId":47},{"fpId":17,"dishId":46},{"fpId":18,"dishId":47},{"fpId":16,"dishId":48},{"fpId":3,"dishId":48},{"fpId":3,"dishId":45},{"fpId":3,"dishId":43},{"fpId":3,"dishId":42},{"fpId":3,"dishId":46},{"fpId":7,"dishId":15},{"fpId":7,"dishId":41},{"fpId":7,"dishId":31},{"fpId":7,"dishId":3},{"fpId":7,"dishId":37},{"fpId":7,"dishId":2},{"fpId":7,"dishId":40},{"fpId":7,"dishId":39},{"fpId":7,"dishId":13},{"fpId":7,"dishId":48},{"fpId":7,"dishId":47},{"fpId":7,"dishId":44},{"fpId":1,"dishId":28},{"fpId":1,"dishId":17},{"fpId":1,"dishId":48},{"fpId":2,"dishId":48},{"fpId":2,"dishId":45},{"fpId":2,"dishId":43},{"fpId":2,"dishId":46},{"fpId":6,"dishId":48},{"fpId":6,"dishId":46},{"fpId":4,"dishId":15},{"fpId":4,"dishId":34},{"fpId":4,"dishId":41},{"fpId":4,"dishId":3},{"fpId":4,"dishId":17},{"fpId":4,"dishId":39},{"fpId":4,"dishId":48},{"fpId":4,"dishId":43},{"fpId":4,"dishId":42},{"fpId":4,"dishId":46},{"fpId":15,"dishId":28},{"fpId":15,"dishId":17},{"fpId":15,"dishId":48},{"fpId":11,"dishId":18},{"fpId":11,"dishId":48},{"fpId":11,"dishId":47},{"fpId":11,"dishId":43},{"fpId":11,"dishId":46},{"fpId":27,"dishId":25},{"fpId":27,"dishId":15},{"fpId":27,"dishId":20},{"fpId":27,"dishId":29},{"fpId":27,"dishId":23},{"fpId":27,"dishId":22},{"fpId":27,"dishId":28},{"fpId":27,"dishId":33},{"fpId":27,"dishId":26},{"fpId":27,"dishId":3},{"fpId":27,"dishId":36},{"fpId":27,"dishId":37},{"fpId":27,"dishId":6},{"fpId":27,"dishId":2},{"fpId":27,"dishId":40},{"fpId":27,"dishId":24},{"fpId":27,"dishId":7},{"fpId":27,"dishId":17},{"fpId":27,"dishId":39},{"fpId":27,"dishId":48},{"fpId":27,"dishId":47},{"fpId":12,"dishId":16},{"fpId":12,"dishId":31},{"fpId":12,"dishId":22},{"fpId":12,"dishId":33},{"fpId":12,"dishId":3},{"fpId":12,"dishId":39},{"fpId":12,"dishId":48},{"fpId":12,"dishId":45},{"fpId":12,"dishId":46},{"fpId":13,"dishId":48},{"fpId":13,"dishId":45},{"fpId":13,"dishId":47},{"fpId":13,"dishId":44},{"fpId":13,"dishId":43},{"fpId":13,"dishId":46},{"fpId":24,"dishId":48},{"fpId":24,"dishId":46},{"fpId":25,"dishId":34},{"fpId":25,"dishId":48},{"fpId":25,"dishId":49},{"fpId":25,"dishId":47},{"fpId":26,"dishId":48},{"fpId":26,"dishId":45},{"fpId":26,"dishId":44},{"fpId":26,"dishId":43},{"fpId":26,"dishId":42},{"fpId":26,"dishId":46},{"fpId":31,"dishId":45},{"fpId":31,"dishId":47},{"fpId":31,"dishId":46},{"fpId":28,"dishId":35},{"fpId":28,"dishId":14},{"fpId":28,"dishId":41},{"fpId":28,"dishId":31},{"fpId":28,"dishId":28},{"fpId":28,"dishId":3},{"fpId":28,"dishId":2},{"fpId":28,"dishId":40},{"fpId":28,"dishId":39},{"fpId":28,"dishId":13},{"fpId":28,"dishId":11},{"fpId":28,"dishId":10},{"fpId":28,"dishId":48},{"fpId":28,"dishId":49},{"fpId":28,"dishId":44},{"fpId":28,"dishId":46},{"fpId":30,"dishId":48},{"fpId":30,"dishId":45},{"fpId":30,"dishId":47},{"fpId":30,"dishId":46},{"fpId":20,"dishId":46},{"fpId":21,"dishId":35},{"fpId":21,"dishId":27},{"fpId":21,"dishId":38},{"fpId":21,"dishId":25},{"fpId":21,"dishId":15},{"fpId":21,"dishId":34},{"fpId":21,"dishId":19},{"fpId":21,"dishId":16},{"fpId":21,"dishId":23},{"fpId":21,"dishId":41},{"fpId":21,"dishId":9},{"fpId":21,"dishId":5},{"fpId":21,"dishId":6},{"fpId":21,"dishId":24},{"fpId":21,"dishId":7},{"fpId":21,"dishId":12},{"fpId":21,"dishId":11},{"fpId":21,"dishId":48},{"fpId":21,"dishId":47},{"fpId":14,"dishId":48},{"fpId":14,"dishId":42},{"fpId":14,"dishId":46},{"fpId":32,"dishId":18},{"fpId":32,"dishId":48},{"fpId":32,"dishId":47},{"fpId":32,"dishId":43},{"fpId":32,"dishId":46},{"fpId":33,"dishId":48},{"fpId":33,"dishId":46},{"fpId":22,"dishId":18},{"fpId":22,"dishId":48},{"fpId":22,"dishId":46},{"fpId":22,"dishId":50},{"fpId":34,"dishId":41},{"fpId":34,"dishId":22},{"fpId":34,"dishId":28},{"fpId":34,"dishId":2},{"fpId":34,"dishId":40},{"fpId":34,"dishId":39},{"fpId":34,"dishId":13},{"fpId":34,"dishId":48},{"fpId":34,"dishId":49},{"fpId":34,"dishId":45},{"fpId":34,"dishId":43},{"fpId":34,"dishId":46}]"""

        prefs.edit()
            .putString("food_points", fpJson)
            .putString("dishes", dishJson)
            .putString("links", linksJson)
            .apply()

        load()
    }
}
