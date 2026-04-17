package com.campus.navigator.data

/**
 * Составная модель «заведение + его блюда» — результат агрегирующего запроса
 * [FoodRepository.getAllWithDishes].
 *
 * Используется в менеджере заведений (MainActivity.openRestaurantManager()),
 * чтобы за один вызов получить и метаданные заведения, и список связанных блюд,
 * не делая дополнительных обращений к репозиторию в цикле.
 */
data class FoodPointWithDishesData(
    /** Полная запись заведения из репозитория. */
    val foodPoint: FoodPointData,
    /** Список блюд, привязанных к этому заведению (может быть пустым). */
    val dishes: List<DishData>
)
