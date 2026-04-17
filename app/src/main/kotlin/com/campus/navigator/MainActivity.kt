package com.campus.navigator

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.campus.navigator.data.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.pow

/**
 * Преобразует позицию SeekBar (0..50) в коэффициент масштаба.
 * Формула: 0.5 * 2^(p/25) — экспоненциальная шкала, при p=25 масштаб = 1.0.
 * Вынесена за пределы класса, так как не зависит от состояния Activity.
 */
private fun progressToZoom(p: Int): Float = (0.5f * 2.0.pow(p / 25.0)).toFloat()

/**
 * Главный экран приложения CampusNavigator.
 *
 * Отвечает за:
 *  - Инициализацию [MapData] и [FoodRepository] при старте.
 *  - Управление режимами редактирования карты (препятствия, проходы, точки питания).
 *  - Выбор начальной и конечной точки маршрута и запуск A*.
 *  - Открытие BottomSheet-меню для управления заведениями и блюдами.
 *  - CRUD-операции над заведениями и блюдами через AlertDialog.
 *  - Поиск заведений по выбранным блюдам и построение маршрута (ГА) в фоновом потоке.
 *
 * Наследует [AppCompatActivity] — необходимо для [BottomSheetDialog] из Material library.
 */
class MainActivity : AppCompatActivity() {

    // ── Виджеты ───────────────────────────────────────────────────────────────

    /** Кастомный View карты — центральный элемент UI. */
    private lateinit var mapView: MapView

    /** Строка статуса — информирует пользователя о текущей операции. */
    private lateinit var tvStatus: TextView

    /** Кнопка переключения слоя непроходимых клеток. */
    private lateinit var btnToggleImpassable: Button

    /** Кнопка переключения сетки клеток. */
    private lateinit var btnToggleGrid: Button

    /** Кнопка запуска поиска пути A*. */
    private lateinit var btnFindPath: Button

    /** Кнопка очистки пути, маршрута и маркеров. */
    private lateinit var btnClear: Button

    /** Кнопка переключения режима добавления препятствий. */
    private lateinit var btnAddObstacle: Button

    /** Кнопка переключения режима добавления проходов. */
    private lateinit var btnAddPassage: Button

    /** Кнопка сброса матрицы проходимости и точек питания к исходному состоянию. */
    private lateinit var btnResetGrid: Button

    /** Кнопка переключения режима расстановки достопримечательностей. */
    private lateinit var btnAddLandmark: Button

    /** Кнопка открытия BottomSheet-меню управления заведениями. */
    private lateinit var btnOpenFoodMenu: Button


    /** Слайдер масштаба карты. */
    private lateinit var seekZoom: SeekBar

    // ── Состояние ─────────────────────────────────────────────────────────────

    /**
     * Репозиторий заведений и блюд (SharedPreferences + JSON).
     * Инициализируется в onCreate — не может быть lateinit val из-за контекста.
     */
    private lateinit var repo: FoodRepository

    /**
     * Фаза выбора точек маршрута: 0 = ожидание старта, 1 = ожидание финиша.
     * Переключается при каждом тапе по проходимой клетке в режиме NONE.
     */
    private var tapPhase = 0

    /** Последнее значение масштаба от слайдера — нужно для вычисления относительного коэффициента. */
    private var lastSliderZoom = progressToZoom(25)

    // ── Кэш A* матрицы достопримечательностей ────────────────────────────────

    /**
     * Кэшированная матрица A* расстояний между всеми достопримечательностями.
     * null = кэш недействителен или ещё считается.
     * Инвалидируется при любом изменении списка достопримечательностей.
     * @Volatile — читается и из фонового потока (ACO), и из UI-потока.
     */
    @Volatile private var landmarkDistCache: LandmarkDistCache? = null

    /**
     * Фоновый поток вычисления A* матрицы.
     * Хранится для возможности ожидания (join) в buildAcoRoute.
     */
    @Volatile private var matrixComputeThread: Thread? = null

    /** Контейнер кэша: снэппированные клетки + матрица расстояний. */
    private data class LandmarkDistCache(
        val snapStart: PathFinder.Cell,
        val snapLMs: List<PathFinder.Cell>,
        val startDists: FloatArray,
        val distMatrix: Array<FloatArray>
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Точка входа Activity.
     * Порядок инициализации:
     *  1. MapData.init — загрузка матрицы проходимости (из бинарного кэша или CSV).
     *  2. FoodRepository — загрузка заведений из SharedPreferences.
     *  3. setContentView — надувание layout.
     *  4. bindViews — привязка виджетов по ID.
     *  5. setupButtons, setupZoomSlider — назначение обработчиков.
     *  6. Назначение колбэков MapView.
     *  7. loadFoodPointsFromRepo — восстановление точек питания на карте.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapData.init(this)
        repo = FoodRepository(this)
        setContentView(R.layout.activity_main)

        bindViews()
        setupButtons()
        setupZoomSlider()

        mapView.onCellTapped       = { row, col -> handleMapTap(row, col) }
        mapView.onLandmarkTapped   = { row, col -> showLandmarkEditDialog(row, col) }
        mapView.onLandmarkChanged  = { saveLandmarks(); precomputeLandmarkMatrix() }
        mapView.onResetClusters    = { resetClusters() }
        mapView.onFoodPointToggled = { row, col, added -> syncFoodPointToggle(row, col, added) }
        // При сбросе матрицы (onSizeChanged) синхронизируем слайдер с реальным масштабом
        mapView.onMatrixReset      = {
            lastSliderZoom = progressToZoom(25)
            seekZoom.progress = 25
        }

        updateStatus("Нажмите на карту для установки начальной точки")
        loadFoodPointsFromRepo()
        loadLandmarks()
    }

    /**
     * Привязывает все виджеты layout к полям класса по их ID.
     * Вызывается ровно один раз из onCreate после setContentView.
     */
    private fun bindViews() {
        mapView             = findViewById(R.id.mapView)
        tvStatus            = findViewById(R.id.tvStatus)
        btnToggleImpassable = findViewById(R.id.btnToggleImpassable)
        btnToggleGrid       = findViewById(R.id.btnToggleGrid)
        btnFindPath         = findViewById(R.id.btnFindPath)
        btnClear            = findViewById(R.id.btnClear)
        btnAddObstacle      = findViewById(R.id.btnAddObstacle)
        btnAddPassage       = findViewById(R.id.btnAddPassage)
        btnResetGrid        = findViewById(R.id.btnResetGrid)
        btnAddLandmark      = findViewById(R.id.btnAddLandmark)
        btnOpenFoodMenu     = findViewById(R.id.btnOpenFoodMenu)
        seekZoom            = findViewById(R.id.seekZoom)
    }

    // ── Кнопки панели управления ──────────────────────────────────────────────

    /**
     * Назначает обработчики кликов всем кнопкам панели управления.
     * Кнопки ADD_OBSTACLE и ADD_PASSAGE используют toggleEditMode для
     * переключения режима с автоматическим изменением подписи.
     */
    private fun setupButtons() {
        btnToggleImpassable.setOnClickListener {
            mapView.showImpassable = !mapView.showImpassable
            btnToggleImpassable.text = if (mapView.showImpassable) "Скрыть матрицу" else "Показать матрицу"
        }
        btnToggleGrid.setOnClickListener {
            mapView.showGrid = !mapView.showGrid
            btnToggleGrid.text = if (mapView.showGrid) "Скрыть сетку" else "Показать сетку"
        }
        btnFindPath.setOnClickListener { findPath() }
        btnClear.setOnClickListener { clearPath() }
        btnAddObstacle.setOnClickListener {
            toggleEditMode(EditMode.ADD_OBSTACLE, btnAddObstacle, "Стоп (препятствия)", "Добавить препятствие")
        }
        btnAddPassage.setOnClickListener {
            toggleEditMode(EditMode.ADD_PASSAGE, btnAddPassage, "Стоп (проходы)", "Добавить проход")
        }
        btnResetGrid.setOnClickListener { resetGrid() }
        btnAddLandmark.setOnClickListener {
            toggleEditMode(EditMode.ADD_LANDMARK, btnAddLandmark, "Стоп (достопримечательности)", "Достопримечательность")
        }
        btnOpenFoodMenu.setOnClickListener { openFoodMenu() }
    }

    /**
     * Переключает режим редактирования: если [mode] уже активен — деактивирует его;
     * иначе — сначала сбрасывает все режимы, затем включает [mode].
     * Меняет текст [button] на [activeLabel] / [inactiveLabel].
     */
    private fun toggleEditMode(mode: EditMode, button: Button, activeLabel: String, inactiveLabel: String) {
        if (mapView.editMode == mode) {
            mapView.editMode = EditMode.NONE; button.text = inactiveLabel
            updateStatus("Нажмите на карту для установки точек пути")
        } else {
            deactivateAllModes(); mapView.editMode = mode; button.text = activeLabel
            updateStatus(when (mode) {
                EditMode.ADD_OBSTACLE   -> "Нажмите на ячейку для переключения препятствия"
                EditMode.ADD_PASSAGE    -> "Нажмите на ячейку для переключения прохода"
                EditMode.ADD_FOOD_POINT -> "Нажмите на ячейку для добавления/удаления точки питания"
                EditMode.ADD_LANDMARK   -> "Нажмите на ячейку для добавления/удаления достопримечательности"
                EditMode.NONE           -> ""
            })
        }
    }

    /**
     * Сбрасывает все активные режимы редактирования в NONE
     * и возвращает кнопкам исходные подписи.
     */
    private fun deactivateAllModes() {
        btnAddObstacle.text = "Добавить препятствие"
        btnAddPassage.text  = "Добавить проход"
        btnAddLandmark.text = "Достопримечательность"
        mapView.editMode    = EditMode.NONE
    }

    // ── Обработка тапа по карте ───────────────────────────────────────────────

    /**
     * Вызывается MapView.onCellTapped при тапе в режиме NONE.
     *
     * Логика:
     *  1. Если тапнули по точке питания — открывает диалог редактирования заведения.
     *  2. Если клетка непроходима — показывает Toast.
     *  3. Иначе — поочерёдно устанавливает startCell (tapPhase=0) и endCell (tapPhase=1).
     */
    private fun handleMapTap(row: Int, col: Int) {
        if (mapView.editMode != EditMode.NONE) return
        val fp = MapData.getFoodPointAt(row, col)
        if (fp != null) { showFoodPointEditDialog(row, col); return }
        if (!MapData.isPassable(row, col)) {
            Toast.makeText(this, "Ячейка ($row, $col) непроходима", Toast.LENGTH_SHORT).show(); return
        }
        when (tapPhase) {
            0 -> {
                mapView.startCell = PathFinder.Cell(row, col)
                mapView.endCell = null; mapView.currentPath = emptyList()
                tapPhase = 1; updateStatus("Старт: ($row, $col). Нажмите для установки конечной точки")
            }
            1 -> {
                mapView.endCell = PathFinder.Cell(row, col)
                tapPhase = 0; updateStatus("Финиш: ($row, $col). Нажмите «Найти путь»")
            }
        }
    }

    // ── Поиск пути ────────────────────────────────────────────────────────────

    /**
     * Запускает поиск пути A* и анимацию обхода.
     * Вычисление выполняется мгновенно на главном потоке;
     * визуализация (анимация посещённых клеток → итоговый путь) — через MapView.startPathAnimation().
     */
    private fun findPath() {
        val s = mapView.startCell; val e = mapView.endCell
        if (s == null || e == null) {
            Toast.makeText(this, "Установите обе точки", Toast.LENGTH_SHORT).show(); return
        }
        val result = PathFinder.findPath(s, e)
        updateStatus(if (result.found) "Путь найден: ${result.path.size} ячеек" else "Путь не найден!")
        // Октильное расстояние старт→финиш — управляет скоростью анимации:
        // чем дальше точки, тем быстрее проигрывается анимация.
        val dr = Math.abs(s.row - e.row).toFloat()
        val dc = Math.abs(s.col - e.col).toFloat()
        val dist = 1f * (dr + dc) + (1.4142f - 2f) * minOf(dr, dc)
        mapView.startPathAnimation(result.animEvents, result.path, dist)
    }

    /**
     * Очищает путь, маркеры, маршрут и подсветку точек питания.
     * Сбрасывает tapPhase в 0 и скрывает кластерный слой.
     */
    private fun clearPath() {
        mapView.cancelAnimation()
        mapView.currentPath = emptyList()
        mapView.startCell = null; mapView.endCell = null; tapPhase = 0; mapView.showClusters = false
        mapView.routeFoodPoints = emptyList(); mapView.routeAStarPath = emptyList()
        mapView.landmarkAStarPath = emptyList(); mapView.routeLandmarks = emptyList()
        mapView.highlightedFoodPoints = emptySet()
        updateStatus("Очищено")
    }

    /**
     * Сбрасывает пользовательские правки матрицы и точки питания в MapData,
     * затем очищает визуальное состояние карты.
     */
    private fun resetGrid() {
        MapData.resetGrid(); clearPath(); deactivateAllModes(); updateStatus("Матрица сброшена")
    }

    // ── Кластеризация ─────────────────────────────────────────────────────────

    /**
     * Запускает кластеризацию Union-Find по всем точкам питания на карте.
     * Результат (назначения + центроиды) сохраняется в MapView для отрисовки зон.
     * Требует минимум 2 точки.
     */
    private fun runClustering() {
        val fps = MapData.getFoodPoints()
        if (fps.size < 2) {
            Toast.makeText(this, "Нужно минимум 2 точки", Toast.LENGTH_SHORT).show(); return
        }
        val result = KMeansClusterer.cluster(fps.map { Pair(it.row, it.col) })
        mapView.clusterAssignments  = result.assignments
        mapView.clusterSingletonIds = result.singletonIds
        mapView.showClusters = true
        val k         = result.centroids.size
        val outliers  = result.singletonIds.size
        val statusMsg = if (outliers > 0)
            "Кластеризация: $k ${clusterWord(k)}, одиночек: $outliers"
        else
            "Кластеризация завершена: $k ${clusterWord(k)}"
        updateStatus(statusMsg)
    }

    /**
     * Возвращает правильную форму слова «кластер» для числа [n]
     * согласно правилам русского склонения.
     */
    private fun clusterWord(n: Int) = when {
        n % 100 in 11..19 -> "кластеров"
        n % 10 == 1        -> "кластер"
        n % 10 in 2..4     -> "кластера"
        else               -> "кластеров"
    }

    /**
     * Скрывает кластерные зоны и сбрасывает назначения кластеров.
     * Вызывается при нажатии кнопки «✕ кластеры» на карте.
     */
    private fun resetClusters() {
        mapView.showClusters = false
        mapView.clusterAssignments  = emptyMap()
        mapView.clusterSingletonIds = emptySet()
        updateStatus("Кластеризация сброшена")
    }

    // ── Слайдер масштаба ──────────────────────────────────────────────────────

    /**
     * Подключает SeekBar к масштабированию карты.
     * При изменении прогресса вычисляет относительный коэффициент (newZoom / lastSliderZoom)
     * и передаёт его в [MapView.applyZoomFromCenter].
     */
    private fun setupZoomSlider() {
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val newZoom = progressToZoom(progress)
                val factor = newZoom / lastSliderZoom; lastSliderZoom = newZoom
                mapView.applyZoomFromCenter(factor)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Синхронизация с репозиторием ──────────────────────────────────────────

    /**
     * Загружает все заведения с флагом isOnMap=true из репозитория в MapData.
     * Вызывается один раз при запуске, чтобы восстановить точки питания на карте
     * после закрытия и повторного открытия приложения.
     */
    private fun loadFoodPointsFromRepo() {
        repo.getAllOnMap().forEach { e ->
            MapData.addFoodPointFromDb(e.row, e.col, e.name, e.review, e.tags, e.id)
        }
        mapView.invalidate()
    }

    /**
     * Синхронизирует действие пользователя (добавление/снятие точки питания в режиме
     * ADD_FOOD_POINT) с репозиторием.
     *
     *  - Если [added]=true: вставляет новую запись или обновляет isOnMap=true для существующей.
     *  - Если [added]=false: обновляет isOnMap=false (точка остаётся в репозитории скрытой).
     *
     * Сохраняет dbId обратно в MapData для последующих операций с репозиторием.
     */
    private fun syncFoodPointToggle(row: Int, col: Int, added: Boolean) {
        if (added) {
            val existing = repo.getByCoords(row, col)
            val id = if (existing != null) {
                repo.updateFoodPoint(existing.copy(isOnMap = true))
                existing.id
            } else {
                repo.insertFoodPoint(row, col)
            }
            MapData.getFoodPointAt(row, col)?.dbId = id
        } else {
            val entity = repo.getByCoords(row, col)
            if (entity != null) repo.updateFoodPoint(entity.copy(isOnMap = false))
        }
    }

    // ── BottomSheet меню точек питания ────────────────────────────────────────

    /**
     * Открывает BottomSheetDialog с шестью действиями:
     *  1. Добавить/убрать точку питания (переключить режим ADD_FOOD_POINT).
     *  2. Запустить кластеризацию.
     *  3. Открыть менеджер заведений.
     *  4. Открыть менеджер блюд.
     *  5. Поиск заведений по блюдам + построение маршрута.
     *  6. Сбросить маршрут и подсветку.
     */
    private fun openFoodMenu() {
        val sheet = BottomSheetDialog(this)
        val view  = layoutInflater.inflate(R.layout.bottom_sheet_food_menu, null)
        sheet.setContentView(view)

        view.findViewById<Button>(R.id.btnSheetAddFoodPoint).setOnClickListener {
            sheet.dismiss(); toggleFoodPointEditMode()
        }
        view.findViewById<Button>(R.id.btnSheetAddLandmark).setOnClickListener {
            sheet.dismiss()
            toggleEditMode(EditMode.ADD_LANDMARK, btnAddLandmark, "Стоп (достопримечательности)", "Достопримечательность")
        }
        view.findViewById<Button>(R.id.btnSheetLandmarkList).setOnClickListener {
            sheet.dismiss(); showLandmarkListDialog()
        }
        view.findViewById<Button>(R.id.btnSheetAcoRoute).setOnClickListener {
            sheet.dismiss(); showLandmarkSelectionDialog()
        }
        view.findViewById<Button>(R.id.btnSheetClustering).setOnClickListener {
            sheet.dismiss(); runClustering()
        }
        view.findViewById<Button>(R.id.btnSheetRestaurants).setOnClickListener {
            sheet.dismiss(); openRestaurantManager()
        }
        view.findViewById<Button>(R.id.btnSheetDishes).setOnClickListener {
            sheet.dismiss(); openDishManager()
        }
        view.findViewById<Button>(R.id.btnSheetDecisionTree).setOnClickListener {
            sheet.dismiss()
            startActivity(android.content.Intent(this, DecisionTreeActivity::class.java))
        }
        view.findViewById<Button>(R.id.btnSheetDishSearch).setOnClickListener {
            sheet.dismiss(); openDishSearch()
        }
        view.findViewById<Button>(R.id.btnSheetClearRoute).setOnClickListener {
            sheet.dismiss()
            mapView.routeFoodPoints       = emptyList()
            mapView.highlightedFoodPoints = emptySet()
            updateStatus("Маршрут и подсветка сброшены")
        }
        sheet.show()
    }

    /**
     * Включает или выключает режим размещения точек питания ADD_FOOD_POINT.
     * При выключении возвращает editMode в NONE.
     */
    private fun toggleFoodPointEditMode() {
        if (mapView.editMode == EditMode.ADD_FOOD_POINT) {
            mapView.editMode = EditMode.NONE
            updateStatus("Режим добавления точек отключён")
        } else {
            deactivateAllModes()
            mapView.editMode = EditMode.ADD_FOOD_POINT
            updateStatus("Нажмите на ячейку для добавления/удаления точки питания")
        }
    }

    // ── Диалог редактирования точки питания ──────────────────────────────────

    /**
     * Загружает данные заведения из MapData и репозитория, затем вызывает
     * buildFoodPointDialog для отображения диалога редактирования.
     * Если точка не найдена в MapData — ничего не делает.
     */
    private fun showFoodPointEditDialog(row: Int, col: Int) {
        val fp     = MapData.getFoodPointAt(row, col) ?: return
        val entity = repo.getByCoords(row, col)
        val allDishes  = repo.getAllDishes()
        val linkedIds  = if (entity != null) repo.getLinkedDishes(entity.id).map { it.id }.toSet() else emptySet()
        buildFoodPointDialog(fp, entity, allDishes, linkedIds)
    }

    /**
     * Строит и показывает AlertDialog для редактирования заведения.
     *
     * Содержимое диалога:
     *  - EditText: название, отзыв, теги.
     *  - CheckBox для каждого блюда из справочника (отмечены привязанные).
     *  - Сообщение «Блюда не созданы» если справочник пуст.
     *  - Разделитель и красная кнопка «Удалить заведение полностью».
     *
     * Кнопки диалога:
     *  - «Сохранить»: обновляет имя/отзыв/теги и список связанных блюд.
     *  - «Убрать с карты»: скрывает точку (isOnMap=false), не удаляет из БД.
     *  - «Отмена»: закрывает без изменений.
     *
     * @param fp        Точка питания из MapData (содержит row/col).
     * @param entity    Запись заведения из репозитория (null если ещё не сохранена).
     * @param allDishes Все блюда из справочника для формирования чекбоксов.
     * @param linkedIds ID блюд, уже привязанных к данному заведению.
     */
    private fun buildFoodPointDialog(
        fp: FoodPoint,
        entity: FoodPointData?,
        allDishes: List<DishData>,
        linkedIds: Set<Long>
    ) {
        val dp  = resources.displayMetrics.density.toInt()
        val pad = 12 * dp

        val scroll = ScrollView(this)
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        scroll.addView(root)

        val etName   = EditText(this).apply { hint = "Название"; setText(fp.name) }
        val etReview = EditText(this).apply {
            hint = "Отзыв"; setText(fp.review); minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val etTags = EditText(this).apply { hint = "Теги (кафе, столовая...)"; setText(fp.tags) }
        root.addView(etName); root.addView(etReview); root.addView(etTags)

        // Чекбоксы блюд — один CheckBox на каждое блюдо в справочнике
        val checkBoxes: List<CheckBox>
        if (allDishes.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "Блюда в заведении:"; textSize = 13f; setPadding(0, pad, 0, 4 * dp)
            })
            checkBoxes = allDishes.map { dish ->
                CheckBox(this).apply {
                    text = dish.name; isChecked = dish.id in linkedIds; tag = dish.id
                    root.addView(this)
                }
            }
        } else {
            checkBoxes = emptyList()
            root.addView(TextView(this).apply {
                text = "Блюда не созданы. Добавьте в «Управление блюдами»."
                textSize = 12f; setTextColor(Color.GRAY); setPadding(0, 8 * dp, 0, 0)
            })
        }

        // Разделитель + кнопка полного удаления заведения
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 12 * dp, 0, 8 * dp) }
            setBackgroundColor(Color.LTGRAY)
        })
        val btnDelete = Button(this).apply {
            text = "Удалить заведение полностью"; setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))
            root.addView(this)
        }

        val btnRate = Button(this).apply {
            text = "Оценить (нейросеть)"; setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1565C0"))
            root.addView(this)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Заведение (${fp.row}, ${fp.col})")
            .setView(scroll)
            .setPositiveButton("Сохранить", null)
            .setNeutralButton("Убрать с карты", null)
            .setNegativeButton("Отмена", null)
            .create()

        // Кнопка удаления показывает подтверждение перед окончательным удалением
        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Удалить заведение?")
                .setMessage("Заведение и все связанные блюда будут удалены безвозвратно.")
                .setPositiveButton("Удалить") { _, _ ->
                    MapData.removeFoodPoint(fp.row, fp.col)
                    entity?.let { repo.deleteFoodPointById(it.id) }
                    mapView.routeFoodPoints = mapView.routeFoodPoints
                        .filter { it.row != fp.row || it.col != fp.col }
                    mapView.highlightedFoodPoints = mapView.highlightedFoodPoints
                        .filter { it.first != fp.row || it.second != fp.col }.toSet()
                    mapView.invalidate(); dialog.dismiss()
                }
                .setNegativeButton("Отмена", null).show()
        }

        btnRate.setOnClickListener {
            dialog.dismiss()
            startActivity(android.content.Intent(this, DigitActivity::class.java))
        }

        // setOnShowListener нужен для переопределения кнопок диалога
        // (без этого AlertDialog закрывается при любом нажатии кнопки)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name    = etName.text.toString().trim()
                val review  = etReview.text.toString().trim()
                val tags    = etTags.text.toString().trim()
                val checked = checkBoxes.filter { it.isChecked }.map { it.tag as Long }.toSet()
                MapData.updateFoodPointMeta(fp.row, fp.col, name, review, tags)
                val ent = entity ?: repo.getByCoords(fp.row, fp.col)
                if (ent != null) {
                    repo.updateFoodPoint(ent.copy(name = name, review = review, tags = tags))
                    repo.clearLinks(ent.id)
                    checked.forEach { dishId -> repo.insertLink(ent.id, dishId) }
                }
                mapView.invalidate(); dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                MapData.removeFoodPoint(fp.row, fp.col)
                val ent = entity ?: repo.getByCoords(fp.row, fp.col)
                if (ent != null) repo.updateFoodPoint(ent.copy(isOnMap = false))
                mapView.routeFoodPoints = mapView.routeFoodPoints
                    .filter { it.row != fp.row || it.col != fp.col }
                mapView.highlightedFoodPoints = mapView.highlightedFoodPoints
                    .filter { it.first != fp.row || it.second != fp.col }.toSet()
                mapView.invalidate(); dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ── Менеджер заведений ────────────────────────────────────────────────────

    /**
     * Загружает список всех заведений с блюдами из репозитория
     * и открывает диалог-список.
     */
    private fun openRestaurantManager() {
        val list = repo.getAllWithDishes()
        showRestaurantListDialog(list)
    }

    /**
     * Показывает AlertDialog со списком всех заведений.
     * Клик по строке открывает меню действий для выбранного заведения.
     * Для каждой строки показывает: значок (на карте/не на карте), название,
     * список привязанных блюд.
     */
    private fun showRestaurantListDialog(list: List<FoodPointWithDishesData>) {
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Заведения")
                .setMessage("Заведений нет. Добавьте точки питания на карту.")
                .setPositiveButton("ОК", null).show()
            return
        }
        val labels = list.map { fwd ->
            val fp    = fwd.foodPoint
            val mark  = if (fp.isOnMap) "📍" else "○"
            val name  = fp.name.ifBlank { "(${fp.row}, ${fp.col})" }
            val dshs  = if (fwd.dishes.isEmpty()) "" else "\n   Блюда: ${fwd.dishes.joinToString { it.name }}"
            "$mark $name$dshs"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Список заведений (${list.size})")
            .setItems(labels) { _, idx ->
                val fp = list[idx].foodPoint
                // Центрируем карту на выбранном заведении (только если оно на карте)
                if (fp.isOnMap) mapView.centerOnCell(fp.row, fp.col)
                showRestaurantActionsDialog(list[idx])
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /**
     * Показывает контекстное меню действий для конкретного заведения [fwd]:
     *  - Редактировать название/теги.
     *  - Убрать с карты / Показать на карте (в зависимости от isOnMap).
     *  - Привязать блюда.
     *  - Удалить заведение полностью.
     */
    private fun showRestaurantActionsDialog(fwd: FoodPointWithDishesData) {
        val fp    = fwd.foodPoint
        val name  = fp.name.ifBlank { "(${fp.row}, ${fp.col})" }
        val onMap = fp.isOnMap

        val actions = arrayOf(
            "Редактировать название / теги",
            if (onMap) "Убрать с карты" else "Показать на карте",
            "Привязать блюда",
            "Удалить заведение"
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions) { _, idx ->
                when (idx) {
                    0 -> showEditRestaurantMetaDialog(fp)
                    1 -> toggleRestaurantOnMap(fp, !onMap)
                    2 -> showLinkDishesDialog(fp, fwd.dishes)
                    3 -> confirmDeleteRestaurant(fp)
                }
            }
            .setNegativeButton("Назад", null).show()
    }

    /**
     * Открывает диалог редактирования текстовых метаданных заведения
     * (название, отзыв, теги) без изменения позиции на карте.
     * После сохранения обновляет данные и в репозитории, и в MapData.
     */
    private fun showEditRestaurantMetaDialog(fp: FoodPointData) {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val etName   = EditText(this).apply { hint = "Название"; setText(fp.name) }
        val etReview = EditText(this).apply { hint = "Отзыв"; setText(fp.review); minLines = 2 }
        val etTags   = EditText(this).apply { hint = "Теги"; setText(fp.tags) }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0)
            addView(etName); addView(etReview); addView(etTags)
        }
        AlertDialog.Builder(this)
            .setTitle("Редактировать заведение")
            .setView(wrap)
            .setPositiveButton("Сохранить") { _, _ ->
                val name   = etName.text.toString().trim()
                val review = etReview.text.toString().trim()
                val tags   = etTags.text.toString().trim()
                repo.updateFoodPoint(fp.copy(name = name, review = review, tags = tags))
                MapData.updateFoodPointMeta(fp.row, fp.col, name, review, tags)
                mapView.invalidate()
            }
            .setNegativeButton("Отмена", null).show()
    }

    /**
     * Переключает видимость заведения на карте.
     * [show]=true: добавляет точку в MapData (если ещё нет) и обновляет repo.
     * [show]=false: удаляет точку из MapData и обновляет repo.
     * После операции повторно открывает менеджер заведений для обновления списка.
     */
    private fun toggleRestaurantOnMap(fp: FoodPointData, show: Boolean) {
        repo.updateFoodPoint(fp.copy(isOnMap = show))
        if (show) {
            MapData.addFoodPointFromDb(fp.row, fp.col, fp.name, fp.review, fp.tags, fp.id)
        } else {
            MapData.removeFoodPoint(fp.row, fp.col)
        }
        mapView.invalidate()
        val msg = if (show) "Заведение добавлено на карту" else "Заведение убрано с карты"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        openRestaurantManager()
    }

    /**
     * Открывает диалог с множественным выбором для привязки/отвязки блюд к заведению [fp].
     * Текущие привязки предварительно отмечены чекбоксами.
     * При сохранении полностью перезаписывает список ссылок (clearLinks + insertLink).
     */
    private fun showLinkDishesDialog(fp: FoodPointData, current: List<DishData>) {
        val all    = repo.getAllDishes()
        if (all.isEmpty()) {
            Toast.makeText(this, "Сначала добавьте блюда в «Управление блюдами»", Toast.LENGTH_LONG).show()
            return
        }
        val linked  = current.map { it.id }.toSet()
        val names   = all.map { it.name }.toTypedArray()
        val checked = BooleanArray(all.size) { all[it].id in linked }

        AlertDialog.Builder(this)
            .setTitle("Блюда в заведении")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Сохранить") { _, _ ->
                val selected = all.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                repo.clearLinks(fp.id)
                selected.forEach { repo.insertLink(fp.id, it) }
            }
            .setNegativeButton("Отмена", null).show()
    }

    /**
     * Запрашивает подтверждение перед полным удалением заведения [fp].
     * При подтверждении удаляет точку из MapData и репозитория (включая все ссылки на блюда).
     */
    private fun confirmDeleteRestaurant(fp: FoodPointData) {
        val name = fp.name.ifBlank { "(${fp.row}, ${fp.col})" }
        AlertDialog.Builder(this)
            .setTitle("Удалить «$name»?")
            .setMessage("Заведение и все связанные блюда будут удалены.")
            .setPositiveButton("Удалить") { _, _ ->
                MapData.removeFoodPoint(fp.row, fp.col)
                repo.deleteFoodPointById(fp.id)
                mapView.invalidate()
                Toast.makeText(this, "Заведение удалено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null).show()
    }

    // ── Менеджер блюд ─────────────────────────────────────────────────────────

    /**
     * Загружает список всех блюд из репозитория и открывает диалог управления.
     */
    private fun openDishManager() {
        showDishManagerDialog(repo.getAllDishes())
    }

    /**
     * Строит и показывает диалог управления глобальным справочником блюд.
     *
     * Для каждого блюда рисует строку: название + кнопка «✎» (переименовать) + «✕» (удалить).
     * Внизу — поле ввода и кнопка «Добавить блюдо».
     *
     * @param dishes Текущий список блюд из репозитория.
     */
    private fun showDishManagerDialog(dishes: List<DishData>) {
        val d   = resources.displayMetrics.density.toInt()
        val pad = 12 * d

        val scroll = ScrollView(this)
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
        }
        scroll.addView(root)

        if (dishes.isEmpty()) {
            root.addView(TextView(this).apply { text = "Блюд пока нет. Добавьте ниже." })
        } else {
            dishes.forEach { dish ->
                // Строка одного блюда: название | кнопка переименовать | кнопка удалить
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 4 * d, 0, 4 * d)
                }
                row.addView(TextView(this).apply {
                    text = dish.name; textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                row.addView(Button(this).apply {
                    text = "✎"; textSize = 12f; setPadding(8 * d, 0, 8 * d, 0)
                    setOnClickListener { showRenameDishDialog(dish) }
                })
                row.addView(Button(this).apply {
                    text = "✕"; textSize = 12f; setPadding(8 * d, 0, 8 * d, 0)
                    setBackgroundColor(Color.parseColor("#FFEBEE"))
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Удалить «${dish.name}»?")
                            .setMessage("Блюдо будет отвязано от всех заведений.")
                            .setPositiveButton("Удалить") { _, _ ->
                                repo.deleteDishById(dish.id)
                                Toast.makeText(this@MainActivity, "Удалено", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Отмена", null).show()
                    }
                })
                root.addView(row)
            }
        }

        // Разделитель между списком и формой добавления
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 8 * d, 0, 8 * d) }
            setBackgroundColor(Color.LTGRAY)
        })

        // Форма добавления нового блюда
        val etNew = EditText(this).apply { hint = "Новое блюдо" }
        root.addView(etNew)
        root.addView(Button(this).apply {
            text = "Добавить блюдо"
            setOnClickListener {
                val name = etNew.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Введите название", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                repo.insertDish(name)
                Toast.makeText(this@MainActivity, "Блюдо «$name» добавлено", Toast.LENGTH_SHORT).show()
                etNew.setText("")
            }
        })

        AlertDialog.Builder(this)
            .setTitle("Управление блюдами")
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    /**
     * Открывает диалог переименования блюда [dish].
     * Не меняет ID и привязки — только имя в репозитории.
     */
    private fun showRenameDishDialog(dish: DishData) {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val et  = EditText(this).apply { setText(dish.name) }
        val wrap = LinearLayout(this).apply { setPadding(pad, pad, pad, 0); addView(et) }
        AlertDialog.Builder(this)
            .setTitle("Переименовать блюдо")
            .setView(wrap)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotEmpty()) repo.updateDish(dish.copy(name = newName))
            }
            .setNegativeButton("Отмена", null).show()
    }

    // ── Поиск по блюдам + маршрут ─────────────────────────────────────────────

    /**
     * Открывает BottomSheet для выбора блюд.
     * Если справочник блюд пуст — показывает подсказку.
     */
    private fun openDishSearch() {
        val dishes = repo.getAllDishes()
        if (dishes.isEmpty()) {
            Toast.makeText(this, "Блюд пока нет. Добавьте через «Управление блюдами».", Toast.LENGTH_LONG).show()
            return
        }
        showDishSearchSheet(dishes)
    }

    /**
     * Строит BottomSheetDialog для выбора блюд поиска.
     * Каждое блюдо отображается чекбоксом. Кнопка «Найти заведения и построить маршрут»
     * собирает выбранные ID и вызывает [searchAndBuildRoute].
     *
     * @param dishes Список блюд из репозитория.
     */
    private fun showDishSearchSheet(dishes: List<DishData>) {
        val sheet = BottomSheetDialog(this)
        val d     = resources.displayMetrics.density.toInt()
        val pad   = 12 * d

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad * 2)
        }

        root.addView(TextView(this).apply {
            text = "Поиск по блюдам"; textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8 * d)
        })

        // Прокручиваемый список чекбоксов ограниченной высоты
        val scrollHeightPx = (250 * resources.displayMetrics.density).toInt()
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, scrollHeightPx
            )
        }
        val checkContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(checkContainer)
        root.addView(scroll)

        val checkBoxes = dishes.map { dish ->
            CheckBox(this).apply { text = dish.name; tag = dish.id; checkContainer.addView(this) }
        }

        root.addView(Button(this).apply {
            text = "Найти заведения и построить маршрут"
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))
            setOnClickListener {
                val selected = checkBoxes.filter { it.isChecked }.map { it.tag as Long }
                if (selected.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Выберите хотя бы одно блюдо", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                sheet.dismiss()
                searchAndBuildRoute(selected)
            }
        })

        sheet.setContentView(root)
        sheet.show()
    }

    /**
     * Выполняет поиск заведений с выбранными блюдами, фильтрует их для эффективного
     * маршрута и запускает построение.
     *
     * Подсвечивает ВСЕ найденные заведения, но маршрут строит только по оптимальному
     * подмножеству (результат [filterRestaurantsForRoute]).
     */
    private fun searchAndBuildRoute(dishIds: List<Long>) {
        updateStatus("Поиск заведений...")
        val found = repo.findOnMapByDishIds(dishIds)
        if (found.isEmpty()) {
            Toast.makeText(this, "Заведений с выбранными блюдами не найдено", Toast.LENGTH_LONG).show()
            updateStatus("Блюда не найдены"); return
        }

        // Подсвечиваем все найденные заведения на карте
        mapView.highlightedFoodPoints = found.map { Pair(it.row, it.col) }.toSet()

        // Фильтруем до оптимального подмножества
        val start    = getStartCell()
        val filtered = filterRestaurantsForRoute(found, dishIds.toSet(), start)

        val filterMsg = if (filtered.size < found.size) " (отобрано ${filtered.size} из ${found.size})" else ""
        updateStatus("Найдено ${found.size} заведений$filterMsg, строю маршрут...")
        buildRoute(filtered)
    }

    /**
     * Отбирает оптимальное подмножество заведений для маршрута.
     *
     * Логика:
     *  1. Для каждого заведения считает, сколько из выбранных блюд оно предлагает.
     *  2. Сортирует по убыванию охвата блюд, затем по расстоянию до старта.
     *  3. Жадно добавляет заведения, пока каждое блюдо не покрыто [maxPerDish] заведениями.
     *
     * Эффект: рестораны с несколькими выбранными блюдами идут первыми и закрывают
     * сразу несколько слотов; чисто «кофейных» мест берётся не более [maxPerDish] ближайших.
     *
     * @param found       Все заведения, у которых есть хотя бы одно выбранное блюдо.
     * @param dishIds     ID выбранных блюд.
     * @param start       Стартовая клетка (row, col) — для сортировки по расстоянию.
     * @param maxPerDish  Максимум заведений на одно блюдо (default = 3).
     */
    private fun filterRestaurantsForRoute(
        found: List<FoodPointData>,
        dishIds: Set<Long>,
        start: Pair<Int, Int>,
        maxPerDish: Int = 3
    ): List<FoodPointData> {
        if (found.size <= maxPerDish) return found   // слишком мало — фильтровать не нужно

        // Заведение → подмножество выбранных блюд которые оно предлагает
        val restaurantDishes: Map<Long, Set<Long>> = found.associate { fp ->
            fp.id to repo.getLinkedDishes(fp.id).map { it.id }.filter { it in dishIds }.toSet()
        }

        // Сортировка: сначала рестораны с наибольшим охватом блюд, затем ближайшие к старту
        val sorted = found.sortedWith(
            compareByDescending<FoodPointData> { restaurantDishes[it.id]?.size ?: 0 }
                .thenBy { fp ->
                    val dr = (fp.row - start.first).toFloat()
                    val dc = (fp.col - start.second).toFloat()
                    dr * dr + dc * dc   // евклидово²  — только для порядка, не для A*
                }
        )

        // Жадный отбор: каждое блюдо включается в маршрут максимум maxPerDish раз
        val dishSlots = dishIds.associateWith { 0 }.toMutableMap()
        val result    = mutableListOf<FoodPointData>()

        for (fp in sorted) {
            val dishes = restaurantDishes[fp.id] ?: emptySet()
            // Добавляем заведение если хотя бы одно его блюдо ещё не добрало лимит
            if (dishes.any { (dishSlots[it] ?: 0) < maxPerDish }) {
                result.add(fp)
                dishes.forEach { dishSlots[it] = (dishSlots[it] ?: 0) + 1 }
            }
        }

        return result
    }

    /**
     * Строит оптимальный маршрут обхода заведений [foodPoints] в фоновом потоке.
     *
     * Шаги:
     *  1. Снэппит каждое заведение и стартовую клетку к ближайшей проходимой клетке
     *     (nearestPassable) — заведения могут стоять внутри зданий.
     *  2. Предвычисляет матрицу A* расстояний между всеми парами снэппированных точек.
     *  3. Запускает ГА с реальными A* весами.
     *  4. Строит отображаемый маршрут через findPathMulti — реальные проходимые ячейки.
     */
    private fun buildRoute(foodPoints: List<FoodPointData>) {
        updateStatus("Вычисление A* матрицы расстояний...")
        val startPair = getStartCell()
        Thread {
            val n         = foodPoints.size
            val INF       = Float.MAX_VALUE / 2f
            val rawStart  = PathFinder.Cell(startPair.first, startPair.second)

            // ── Снэппинг всех точек на проходимые клетки ─────────────────────
            // Заведения могут быть расставлены на стенах/зданиях — берём ближайшую
            // проходимую клетку, чтобы A* мог работать от/до каждой точки.
            val snapStart = PathFinder.nearestPassable(rawStart.row, rawStart.col) ?: rawStart
            val snapWPs   = foodPoints.map { fp ->
                PathFinder.nearestPassable(fp.row, fp.col)
                    ?: PathFinder.Cell(fp.row, fp.col)
            }

            // ── Матрица A* расстояний ─────────────────────────────────────────
            val startDists = FloatArray(n) { i ->
                PathFinder.findPathCost(snapStart, snapWPs[i])
                    .let { if (it == Float.MAX_VALUE) INF else it }
            }
            val distMatrix = Array(n) { FloatArray(n) }
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val cost = PathFinder.findPathCost(snapWPs[i], snapWPs[j])
                        .let { if (it == Float.MAX_VALUE) INF else it }
                    distMatrix[i][j] = cost
                    distMatrix[j][i] = cost
                }
            }

            runOnUiThread { updateStatus("Запуск генетического алгоритма...") }

            // ── Генетический алгоритм ─────────────────────────────────────────
            val result = GeneticRouteSolver.solveWithMatrix(n, startDists, distMatrix) { indices, dist ->
                // Вызывается каждые 30 поколений — показываем текущий лучший маршрут
                val interimFP = indices
                    .map { foodPoints[it] }
                    .mapNotNull { e -> MapData.getFoodPointAt(e.row, e.col) }
                runOnUiThread {
                    mapView.routeFoodPoints = interimFP
                    mapView.routeAStarPath  = emptyList()
                    updateStatus("ГА: ~${dist.toInt()} кл. (поиск...)")
                }
            }
            val ordered   = result.orderedIndices.map { foodPoints[it] }
            val orderedFP = ordered.mapNotNull { e -> MapData.getFoodPointAt(e.row, e.col) }

            // ── A* пути для отрисовки через findPathMulti ─────────────────────
            // Порядок точек: snapStart → snapped[ordered[0]] → snapped[ordered[1]] → …
            val orderedSnapped = result.orderedIndices.map { snapWPs[it] }
            val pathCells = PathFinder.findPathMulti(snapStart, orderedSnapped)

            val dist  = result.totalDistanceCells.toInt()
            val names = ordered.joinToString(" → ") { it.name.ifBlank { "(${it.row},${it.col})" } }
            runOnUiThread {
                mapView.routeFoodPoints = orderedFP
                mapView.routeAStarPath  = pathCells
                updateStatus("Маршрут (${orderedFP.size} заведений, ~$dist кл.): $names")
            }
        }.start()
    }

    /**
     * Определяет стартовую клетку для маршрута ГА.
     * Приоритет: startCell (выбранная пользователем) → GPS (заглушка) → центр карты.
     * Настоящая геопривязка требует калибровки координат кампуса — оставлена как .
     */
    private fun getStartCell(): Pair<Int, Int> {
        mapView.startCell?.let { return Pair(it.row, it.col) }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            for (prov in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    @Suppress("MissingPermission")
                    lm.getLastKnownLocation(prov)?.let {
                        //  заменить на реальные координаты кампуса для геопривязки
                        return Pair(MapData.ROWS / 2, MapData.COLS / 2)
                    }
                } catch (_: Exception) { }
            }
        }
        return Pair(MapData.ROWS / 2, MapData.COLS / 2)
    }

    // ── ACO маршрут по достопримечательностям ────────────────────────────────

    /**
     * Показывает диалог выбора достопримечательностей (чекбоксы).
     * После подтверждения запускает ACO только по выбранным точкам.
     */
    private fun showLandmarkSelectionDialog() {
        val all = MapData.getLandmarks()
        if (all.isEmpty()) {
            Toast.makeText(this, "Нет достопримечательностей на карте", Toast.LENGTH_SHORT).show()
            return
        }
        val names   = all.map { it.name.ifBlank { "(${it.row}, ${it.col})" } }.toTypedArray()
        val checked = BooleanArray(all.size) { true }   // по умолчанию все выбраны

        AlertDialog.Builder(this)
            .setTitle("Выберите достопримечательности")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Построить маршрут") { _, _ ->
                val selected = all.filterIndexed { i, _ -> checked[i] }
                when {
                    selected.isEmpty() ->
                        Toast.makeText(this, "Выберите хотя бы одну точку", Toast.LENGTH_SHORT).show()
                    selected.size == 1 -> {
                        mapView.centerOnCell(selected[0].row, selected[0].col)
                        updateStatus("Выбрана одна точка: ${selected[0].name.ifBlank { "(${selected[0].row},${selected[0].col})" }}")
                    }
                    else -> buildAcoRoute(selected)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Запускает фоновое вычисление полной A* матрицы для ВСЕХ достопримечательностей.
     * Вызывается сразу после загрузки/изменения списка, чтобы к моменту нажатия
     * кнопки ACO матрица уже была готова в памяти.
     * Если предыдущий поток ещё работает — прерывает его и начинает заново.
     */
    private fun precomputeLandmarkMatrix() {
        val landmarks = MapData.getLandmarks()
        landmarkDistCache = null
        matrixComputeThread?.interrupt()

        if (landmarks.isEmpty()) return

        val startPair = getStartCell()
        val rawStart  = PathFinder.Cell(startPair.first, startPair.second)
        val INF       = 500_000f
        val n         = landmarks.size

        runOnUiThread { updateStatus("Вычисление A* матрицы ($n точек)...") }

        val t = Thread {
            val snapRawStart = PathFinder.nearestPassable(rawStart.row, rawStart.col) ?: rawStart
            val snapLMs = landmarks.map { lm ->
                PathFinder.nearestPassable(lm.row, lm.col) ?: PathFinder.Cell(lm.row, lm.col)
            }

            val startDists = FloatArray(n)
            for (i in 0 until n) {
                if (Thread.currentThread().isInterrupted) return@Thread
                startDists[i] = PathFinder.findPathCost(snapRawStart, snapLMs[i])
                    .let { if (it == Float.MAX_VALUE) INF else it }
            }

            val distMatrix = Array(n) { FloatArray(n) }
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    val cost = PathFinder.findPathCost(snapLMs[i], snapLMs[j])
                        .let { if (it == Float.MAX_VALUE) INF else it }
                    distMatrix[i][j] = cost
                    distMatrix[j][i] = cost
                }
            }

            landmarkDistCache = LandmarkDistCache(snapRawStart, snapLMs, startDists, distMatrix)
            runOnUiThread { updateStatus("Матрица A* готова ($n точек)") }
        }
        t.isDaemon = true
        matrixComputeThread = t
        t.start()
    }

    /**
     * Запускает ACO для выбранных достопримечательностей.
     * Если матрица ещё считается — ждёт её завершения (join), затем сразу запускает ACO.
     * Если кэш готов — запускает ACO мгновенно без пересчёта.
     */
    private fun buildAcoRoute(selected: List<Landmark>) {
        val allLandmarks = MapData.getLandmarks()
        updateStatus("Подготовка ACO (выбрано ${selected.size} из ${allLandmarks.size})...")

        Thread {
            // Ждём фоновый поток вычисления матрицы, если он ещё работает
            matrixComputeThread?.let { t ->
                if (t.isAlive) {
                    runOnUiThread { updateStatus("Ожидание готовности матрицы A*...") }
                    t.join()
                }
            }

            val fullCache = landmarkDistCache
            if (fullCache == null) {
                runOnUiThread { updateStatus("Ошибка: матрица A* недоступна") }
                return@Thread
            }

            val indices   = selected.map { sel -> allLandmarks.indexOfFirst { it.row == sel.row && it.col == sel.col } }
            val n         = indices.size
            val subMatrix = Array(n) { i -> FloatArray(n) { j -> fullCache.distMatrix[indices[i]][indices[j]] } }
            val subSnaps  = indices.map { fullCache.snapLMs[it] }

            val startPair = getStartCell()
            val rawStart  = PathFinder.Cell(startPair.first, startPair.second)
            val snapStart = PathFinder.nearestPassable(rawStart.row, rawStart.col) ?: rawStart
            val INF       = 500_000f
            val subStart  = FloatArray(n) { i ->
                PathFinder.findPathCost(snapStart, subSnaps[i])
                    .let { if (it == Float.MAX_VALUE) INF else it }
            }

            runOnUiThread { updateStatus("Запуск муравьиного алгоритма ($n точек)...") }

            val result       = AntColonySolver.solve(n, subStart, subMatrix)
            val orderedLMs   = result.orderedIndices.map { selected[it] }
            val orderedSnaps = result.orderedIndices.map { subSnaps[it] }

            val pathCells = PathFinder.findPathMulti(snapStart, orderedSnaps)

            val dist  = result.totalDistance.toInt()
            val names = orderedLMs.joinToString(" → ") { it.name.ifBlank { "(${it.row},${it.col})" } }

            runOnUiThread {
                mapView.landmarkAStarPath = pathCells
                mapView.routeLandmarks    = orderedLMs
                updateStatus("ACO маршрут ($n точек, ~$dist кл.): $names")
            }
        }.start()
    }

    // ── Персистентность достопримечательностей ────────────────────────────────

    /**
     * Сохраняет все достопримечательности в SharedPreferences как JSON-массив.
     * Формат: [{"row":123,"col":456,"name":"Фонтан"}, ...]
     * Вызывается при каждом изменении (добавление, удаление, переименование).
     */
    private fun saveLandmarks() {
        val arr = org.json.JSONArray()
        MapData.getLandmarks().forEach { lm ->
            arr.put(org.json.JSONObject().apply {
                put("row",  lm.row)
                put("col",  lm.col)
                put("name", lm.name)
            })
        }
        getSharedPreferences("landmarks", MODE_PRIVATE)
            .edit().putString("data", arr.toString()).apply()
    }

    /**
     * Загружает достопримечательности из SharedPreferences при старте приложения.
     * Добавляет каждую в MapData и обновляет карту.
     */
    private fun loadLandmarks() {
        val json = getSharedPreferences("landmarks", MODE_PRIVATE)
            .getString("data", null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj  = arr.getJSONObject(i)
                val row  = obj.getInt("row")
                val col  = obj.getInt("col")
                val name = obj.optString("name", "")
                MapData.addLandmark(row, col)
                MapData.getLandmarkAt(row, col)?.name = name
            }
        } catch (_: Exception) { }
        mapView.invalidate()
        precomputeLandmarkMatrix()
    }

    // ── Достопримечательности ─────────────────────────────────────────────────

    /**
     * Показывает список всех достопримечательностей.
     * Клик по строке — центрирует камеру на объекте и открывает диалог редактирования.
     */
    private fun showLandmarkListDialog() {
        val landmarks = MapData.getLandmarks()
        if (landmarks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Достопримечательности")
                .setMessage("Достопримечательностей нет. Расставьте их на карте.")
                .setPositiveButton("ОК", null).show()
            return
        }
        val labels = landmarks.map { lm ->
            lm.name.ifBlank { "(${lm.row}, ${lm.col})" }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Достопримечательности (${landmarks.size})")
            .setItems(labels) { _, idx ->
                val lm = landmarks[idx]
                mapView.centerOnCell(lm.row, lm.col)
                showLandmarkEditDialog(lm.row, lm.col)
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /**
     * Диалог редактирования достопримечательности: изменить название или удалить.
     * Название хранится в памяти (MapData.Landmark.name).
     */
    private fun showLandmarkEditDialog(row: Int, col: Int) {
        val lm = MapData.getLandmarkAt(row, col) ?: return
        val pad = (12 * resources.displayMetrics.density).toInt()

        val etName = EditText(this).apply {
            hint = "Название"; setText(lm.name)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0)
            addView(etName)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Достопримечательность")
            .setView(wrap)
            .setPositiveButton("Сохранить", null)
            .setNeutralButton("Удалить") { _, _ ->
                MapData.removeLandmark(row, col)
                saveLandmarks()
                precomputeLandmarkMatrix()
                mapView.invalidate()
                updateStatus("Достопримечательность удалена")
            }
            .setNegativeButton("Отмена", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                lm.name = etName.text.toString().trim()
                saveLandmarks()
                mapView.invalidate()
                dialog.dismiss()
                updateStatus("Название сохранено: ${lm.name.ifBlank { "(${row}, ${col})" }}")
            }
        }
        dialog.show()
    }
    // ── Вспомогательный метод ─────────────────────────────────────────────────

    /** Обновляет текст статусной строки. Вызывается только на главном потоке. */
    private fun updateStatus(msg: String) { tvStatus.text = msg }
}
