package com.campus.navigator

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Режим редактирования карты, активный в данный момент.
 *
 *  - NONE          — обычный режим (тап открывает меню точки или выбирает клетку для маршрута);
 *  - ADD_OBSTACLE  — тап переключает проходимость клетки в «непроходима»;
 *  - ADD_PASSAGE   — тап переключает проходимость клетки в «проходима»;
 *  - ADD_FOOD_POINT — тап добавляет / убирает точку питания на карте.
 */
enum class EditMode {
    NONE, ADD_OBSTACLE, ADD_PASSAGE, ADD_FOOD_POINT, ADD_LANDMARK
}

/**
 * Кастомный View для отображения карты кампуса и взаимодействия с ней.
 *
 * Возможности:
 *  - Отображение растровой подложки (campus_map.png) с масштабированием под экран.
 *  - Отрисовка сетки клеток, непроходимых зон, изменённых клеток.
 *  - Визуализация пути A* и посещённых клеток.
 *  - Круги точек питания с цветами кластеров.
 *  - Маршрут (полилиния + номера) и подсветка результатов поиска по блюдам.
 *  - Зоны кластеров (blob-заливка) с кнопкой сброса.
 *  - Жесты: одним пальцем — перетаскивание, двумя — масштабирование + перемещение.
 *  - Тап-детекция: в режиме NONE — сначала проверяет попадание в круг точки питания
 *    (радиус 5 клеток), затем — обычную клетку.
 *
 * Координатная система: в canvas-пространстве (mapBitmap coords).
 * Matrix / invertMatrix — преобразование экранных координат ↔ bitmap-координаты.
 */
class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Публичные свойства состояния ──────────────────────────────────────────

    /** Показывать ли сетку клеток поверх карты. */
    var showGrid = false
        set(value) { field = value; invalidate() }

    /**
     * Показывать ли слой непроходимых клеток (красная заливка).
     * При первом включении запускает фоновую сборку impassableBitmap.
     */
    var showImpassable = false
        set(value) {
            field = value
            if (value && impassableBitmap == null && width > 0) buildImpassableBitmapAsync()
            invalidate()
        }

    /** Показывать ли зоны кластеров и кнопку сброса кластеризации. */
    var showClusters = false
        set(value) { field = value; invalidate() }

    /** Список клеток текущего найденного пути (желтая дорожка). */
    var currentPath: List<PathFinder.Cell> = emptyList()
        set(value) { field = value; invalidate() }

    /** Начальная клетка маршрута (зелёный маркер). */
    var startCell: PathFinder.Cell? = null
        set(value) { field = value; invalidate() }

    /** Конечная клетка маршрута (красный маркер). */
    var endCell: PathFinder.Cell? = null
        set(value) { field = value; invalidate() }

    /** Текущий режим редактирования. Смена режима перерисовывает View. */
    var editMode: EditMode = EditMode.NONE
        set(value) { field = value; invalidate() }

    /** Карта (row, col) → ID кластера после выполнения кластеризации. */
    var clusterAssignments: Map<Pair<Int, Int>, Int> = emptyMap()
        set(value) {
            field = value
            clusterIdByCell.clear()
            for ((pt, id) in value) clusterIdByCell[pt.first * MapData.COLS + pt.second] = id
            rebuildClusterGroups()
            invalidate()
        }

    private val clusterIdByCell = HashMap<Int, Int>()

    private val clusterGroups = HashMap<Int, MutableList<Pair<Int, Int>>>()

    /**
     * Множество ID кластеров-одиночек (аутлайеры: точки, которые слишком далеко
     * от всех остальных и не вошли ни в одну группу). Такие точки питания
     * отрисовываются оранжевым цветом, а их blob-зона не рисуется.
     */
    var clusterSingletonIds: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    /** Колбэк: вызывается при тапе по клетке в режиме NONE. */
    var onCellTapped: ((row: Int, col: Int) -> Unit)? = null

    /** Колбэк: вызывается при тапе по кружку достопримечательности в режиме NONE. */
    var onLandmarkTapped: ((row: Int, col: Int) -> Unit)? = null

    /** Колбэк: вызывается при добавлении или удалении достопримечательности в режиме ADD_LANDMARK. */
    var onLandmarkChanged: (() -> Unit)? = null

    /** Колбэк: вызывается при нажатии кнопки «✕ кластеры». */
    var onResetClusters: (() -> Unit)? = null

    /**
     * Колбэк: вызывается когда матрица трансформации сбрасывается в resetMatrixToFit
     * (например при изменении размера View). MainActivity использует его для синхронизации
     * SliderBar с фактическим масштабом после сброса.
     */
    var onMatrixReset: (() -> Unit)? = null

    /**
     * A* пути маршрута заведений — ячейки реального пути обхода (с учётом матрицы
     * проходимости). Рисуется жёлтыми клетками вместо прямых линий.
     */
    var routeAStarPath: List<PathFinder.Cell> = emptyList()
        set(value) { field = value; invalidate() }

    /**
     * A* пути маршрута ACO по достопримечательностям.
     * Рисуется светло-зелёными клетками, отдельно от маршрута заведений.
     */
    var landmarkAStarPath: List<PathFinder.Cell> = emptyList()
        set(value) { field = value; invalidate() }

    /**
     * Достопримечательности в порядке обхода ACO — для нумерованных маркеров.
     */
    var routeLandmarks: List<Landmark> = emptyList()
        set(value) { field = value; invalidate() }

    /**
     * Колбэк при добавлении (added=true) или удалении (added=false)
     * точки питания в режиме ADD_FOOD_POINT.
     * Используется в MainActivity для синхронизации с FoodRepository.
     */
    var onFoodPointToggled: ((row: Int, col: Int, added: Boolean) -> Unit)? = null

    /**
     * Упорядоченный список точек питания для отрисовки маршрута:
     * полилиния соединяет центры кругов в порядке списка,
     * каждая точка получает номер (1, 2, 3 …).
     */
    var routeFoodPoints: List<FoodPoint> = emptyList()
        set(value) { field = value; invalidate() }

    /**
     * Координаты точек питания, найденных поиском по блюду.
     * Вокруг каждой из них рисуется жёлтое кольцо-подсветка.
     */
    var highlightedFoodPoints: Set<Pair<Int, Int>> = emptySet()
        set(value) { field = value; invalidate() }

    // ── Состояние анимации A* ─────────────────────────────────────────────────

    /** Handler главного потока — используется для планирования кадров анимации. */
    private val animHandler = Handler(Looper.getMainLooper())

    /** Runnable текущего кадра анимации; null когда анимация не идёт. */
    private var animRunnable: Runnable? = null

    /**
     * Упакованный массив событий OPEN/CLOSE, полученный из PathResult.animEvents.
     * Пустой когда анимация не запускалась или была сброшена.
     * Кодировка: event ≥ 0 → OPEN (ячейка добавлена в frontier), idx = event;
     *            event < 0  → CLOSE (ячейка перешла в closed),    idx = -event - 1.
     */
    private var animEvents: IntArray = IntArray(0)

    /** Индекс следующего события в [animEvents] для обработки. */
    private var animEventIdx = 0

    /**
     * Ячейки, находящиеся в данный момент в открытом списке A* (frontier).
     * Рисуются оранжевым — «рассматриваются прямо сейчас».
     * При получении CLOSE-события ячейка удаляется из этого множества.
     */
    private val animFrontierCells = HashSet<PathFinder.Cell>(1024)

    /**
     * Ячейки, уже обработанные A* (закрытый список).
     * Рисуются голубым — «уже выбраны и проверены».
     * Накапливается по мере воспроизведения CLOSE-событий.
     */
    private val animClosedCells = ArrayList<PathFinder.Cell>(1024)

    /** Итоговый путь, который показывается после завершения анимации обхода. */
    private var animFinalPath: List<PathFinder.Cell> = emptyList()

    // ── Цвета кластеров ───────────────────────────────────────────────────────

    /**
     * Палитра из 11 цветов для кластерных зон и кругов точек питания.
     * Индекс цвета = ID кластера. При числе кластеров > 11 цвета повторяются.
     */
    val clusterColors = listOf(
        Color.rgb(160, 30, 220),  // фиолетовый
        Color.rgb(30, 110, 220),  // синий
        Color.rgb(30, 170, 60),   // зелёный
        Color.rgb(220, 120, 0),   // оранжевый
        Color.rgb(210, 30, 60),   // красный
        Color.rgb(0, 175, 195),   // бирюзовый
        Color.rgb(200, 180, 0),   // жёлтый
        Color.rgb(0, 140, 100),   // изумрудный
        Color.rgb(220, 60, 160),  // розовый
        Color.rgb(90, 60, 200),   // индиго
        Color.rgb(140, 90, 20)    // коричневый
    )

    /** Прозрачность кластерных зон (0–255); 125 ≈ 49% непрозрачности. */
    private val CLUSTER_ZONE_ALPHA = 125

    // ── Кисти для отрисовки ───────────────────────────────────────────────────

    /** Кисть для растровых изображений с билинейной фильтрацией. */
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Полупрозрачная красная заливка непроходимых клеток базовой матрицы. */
    private val impassablePaint = Paint().apply { color = Color.argb(80, 220, 0, 0); style = Paint.Style.FILL }

    /** Тёмно-красная заливка клеток с пользовательским препятствием. */
    private val obstacleModPaint = Paint().apply { color = Color.argb(160, 180, 0, 0); style = Paint.Style.FILL }

    /** Зелёная заливка клеток с пользовательским проходом. */
    private val passageModPaint = Paint().apply { color = Color.argb(160, 0, 180, 0); style = Paint.Style.FILL }

    /** Голубая заливка клеток закрытого списка A* (уже обработаны). */
    private val visitedPaint = Paint().apply { color = Color.argb(100, 100, 180, 255); style = Paint.Style.FILL }

    /** Оранжевая заливка клеток открытого списка A* (frontier — рассматриваются сейчас). */
    private val frontierPaint = Paint().apply { color = Color.argb(160, 255, 160, 30); style = Paint.Style.FILL }

    /** Жёлтая заливка клеток найденного пути. */
    private val pathPaint = Paint().apply { color = Color.argb(200, 255, 220, 0); style = Paint.Style.FILL }

    /** Тонкая серая сетка клеток. */
    private val gridPaint = Paint().apply { color = Color.argb(60, 100, 100, 100); style = Paint.Style.STROKE; strokeWidth = 0.5f }

    /** Зелёный круг маркера начальной точки пути. */
    private val startPaint = Paint().apply { color = Color.argb(220, 0, 200, 0); style = Paint.Style.FILL; isAntiAlias = true }

    /** Красный круг маркера конечной точки пути. */
    private val endPaint = Paint().apply { color = Color.argb(220, 220, 0, 0); style = Paint.Style.FILL; isAntiAlias = true }

    /** Белая обводка маркеров начала и конца пути. */
    private val markerBorderPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }

    /** Полупрозрачная заливка большого круга точки питания (цвет меняется по кластеру). */
    private val foodFillPaint = Paint().apply { color = Color.argb(70, 255, 165, 0); style = Paint.Style.FILL; isAntiAlias = true }

    /** Обводка большого круга точки питания. */
    private val foodBorderPaint = Paint().apply { color = Color.argb(200, 180, 90, 0); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }

    /** Заливка маленькой точки в центре круга. */
    private val foodDotPaint = Paint().apply { color = Color.argb(230, 255, 140, 0); style = Paint.Style.FILL; isAntiAlias = true }

    /** Обводка маленькой точки в центре. */
    private val foodDotBorderPaint = Paint().apply { color = Color.argb(255, 120, 60, 0); style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }

    /** Полупрозрачная зелёная заливка большого круга достопримечательности. */
    private val landmarkFillPaint = Paint().apply { color = Color.argb(70, 30, 180, 60); style = Paint.Style.FILL; isAntiAlias = true }

    /** Зелёная обводка большого круга достопримечательности. */
    private val landmarkBorderPaint = Paint().apply { color = Color.argb(200, 0, 140, 40); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }

    /** Заливка маленькой точки в центре достопримечательности. */
    private val landmarkDotPaint = Paint().apply { color = Color.argb(230, 20, 200, 60); style = Paint.Style.FILL; isAntiAlias = true }

    /** Обводка маленькой точки достопримечательности. */
    private val landmarkDotBorderPaint = Paint().apply { color = Color.rgb(0, 120, 30); style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }

    /** Заливка зоны кластера (цвет устанавливается динамически перед каждым drawCircle). */
    private val clusterFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Кисть для saveLayer кластерного слоя (контролирует прозрачность всего слоя). */
    private val clusterLayerPaint = Paint()

    // ── Кисти маршрута ────────────────────────────────────────────────────────

    /** Синяя полилиния маршрута между точками питания. */
    private val routeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 30, 130, 255); style = Paint.Style.STROKE
        strokeWidth = 6f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    /** Синий круг-подложка под номером точки маршрута. */
    private val routeNumBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 20, 110, 230); style = Paint.Style.FILL
    }

    /** Белый текст номера точки маршрута (центрирован по кругу). */
    private val routeNumTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }

    /** Жёлтое кольцо-подсветка вокруг точки, найденной при поиске по блюду. */
    private val highlightRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 230, 0); style = Paint.Style.STROKE; strokeWidth = 7f
    }

    /** Светло-зелёная заливка ячеек A* маршрута ACO по достопримечательностям. */
    private val landmarkPathPaint = Paint().apply {
        color = Color.argb(200, 80, 220, 100); style = Paint.Style.FILL
    }

    /** Синий круг-подложка под номером достопримечательности маршрута ACO. */
    private val lmRouteNumBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 20, 140, 50); style = Paint.Style.FILL
    }

    /** Белый текст номера достопримечательности в маршруте ACO. */
    private val lmRouteNumTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }

    // ── Кнопка сброса кластеров (overlay) ────────────────────────────────────

    /** Прямоугольник кнопки «✕ кластеры» в экранных координатах (для hit-test). */
    private val resetBtnRect = RectF()

    /** Фон кнопки сброса кластеров (фиолетовый). */
    private val resetBtnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 80, 20, 120); style = Paint.Style.FILL
    }

    /** Светлая обводка кнопки сброса. */
    private val resetBtnBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 200, 150, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }

    /** Белый текст кнопки сброса. */
    private val resetBtnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; isFakeBoldText = true
    }

    /** Вспомогательный Rect для замера текста кнопки. */
    private val resetBtnTextBounds = Rect()

    // Плотность экрана — константа, кэшируется один раз
    private val density = context.resources.displayMetrics.density

    // Геометрия кнопки — считается один раз, не зависит от размера View
    private val resetBtnPadH = 18f * density
    private val resetBtnPadV = 10f * density
    private val resetBtnLeft = 12f * density
    private val resetBtnText = "✕ кластеры"
    private var resetBtnW = 0f
    private var resetBtnH = 0f

    // ── Bitmap и матрица трансформации ────────────────────────────────────────

    /** Растровая подложка карты кампуса, загружается из R.drawable.campus_map. */
    private var mapBitmap: Bitmap? = null

    /**
     * Матрица трансформации «bitmap → экран»: содержит текущее смещение и масштаб.
     * Изменяется при жестах перетаскивания и масштабирования.
     */
    private val matrix = Matrix()

    /**
     * Обратная матрица «экран → bitmap».
     * Пересчитывается каждый раз при изменении [matrix].
     * Используется для перевода координат тапа в координаты клетки.
     */
    private val invertMatrix = Matrix()

    // ── Кэш непроходимых клеток ───────────────────────────────────────────────

    /**
     * Битмап непроходимых клеток, строится один раз в фоновом потоке.
     * @Volatile — гарантирует видимость между UI-потоком и рабочим потоком.
     * null до завершения построения.
     */
    @Volatile private var impassableBitmap: Bitmap? = null

    /** Флаг, предотвращающий запуск нескольких параллельных потоков построения. */
    @Volatile private var impassableBitmapBuilding = false

    // ── Переменные состояния жестов ───────────────────────────────────────────

    /**
     * Координаты последней позиции пальца — для вычисления дельты при перетаскивании.
     * lastTouchX/Y обновляются в ACTION_MOVE; touchDownX/Y — в ACTION_DOWN для детектирования тапа.
     */
    private var lastTouchX = 0f; private var lastTouchY = 0f

    /** Расстояние между двумя пальцами в начале щипка — базис для масштабирования. */
    private var lastPointerDist = 0f

    /** true, если пользователь сейчас касается экрана двумя пальцами. */
    private var isMultiTouch = false

    /**
     * Середина между двумя пальцами при щипке — центр масштабирования.
     * Обновляется в каждом ACTION_MOVE для корректного двойного жеста (pinch + pan).
     */
    private var midPointX = 0f; private var midPointY = 0f

    /** Координаты начала касания — для определения, был ли жест тапом (< TAP_THRESHOLD пикселей). */
    private var touchDownX = 0f; private var touchDownY = 0f

    /** Максимальное смещение пальца (px), при котором жест считается тапом, а не перетаскиванием. */
    private val TAP_THRESHOLD = 12f

    /**
     * true, если в текущем касании уже было нарисовано хотя бы одно препятствие/проход
     * в режиме ADD_OBSTACLE / ADD_PASSAGE.  Используется в ACTION_UP, чтобы не вызывать
     * handleTap (который бы переключил только одну клетку) после завершения мазка.
     */
    private var isPainting = false

    /**
     * Последняя клетка, обработанная в режиме рисования (row, col).
     * Предотвращает многократное изменение одной и той же клетки при медленном движении пальца.
     */
    private var lastPaintedCell: Pair<Int, Int>? = null

    /** Интервал между кадрами анимации A* (мс). 16 мс ≈ 60 fps. */
    private val ANIM_DELAY_MS = 16L

    /** Пауза между концом анимации обхода и появлением финального пути (мс). */
    private val ANIM_PATH_DELAY_MS = 250L

    init {
        loadMapBitmap()
        // Замеряем кнопку один раз — textSize и текст не меняются
        resetBtnTextPaint.textSize = 12f * density
        resetBtnTextPaint.getTextBounds(resetBtnText, 0, resetBtnText.length, resetBtnTextBounds)
        resetBtnW = resetBtnTextBounds.width() + resetBtnPadH * 2
        resetBtnH = resetBtnTextBounds.height() + resetBtnPadV * 2
    }

    // ── Построение кэша непроходимых клеток ──────────────────────────────────

    /**
     * Строит bitmap непроходимых клеток в отдельном потоке.
     * Итерирует базовую матрицу и закрашивает красным все непроходимые клетки.
     * По завершении вызывает post{invalidate()} для обновления UI.
     * Защищён от повторного запуска флагом impassableBitmapBuilding.
     */
    private fun buildImpassableBitmapAsync() {
        if (impassableBitmapBuilding) return
        impassableBitmapBuilding = true
        impassableBitmap?.recycle()
        impassableBitmap = null
        val bmpW = mapBitmap?.width ?: MapData.COLS
        val bmpH = mapBitmap?.height ?: MapData.ROWS
        Thread {
            val ib = try {
                Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) { impassableBitmapBuilding = false; return@Thread }
            val c = Canvas(ib)
            val cw = bmpW.toFloat() / MapData.COLS
            val ch = bmpH.toFloat() / MapData.ROWS
            val p = Paint().apply { color = Color.argb(80, 220, 0, 0); style = Paint.Style.FILL }
            for (r in 0 until MapData.ROWS)
                for (col in 0 until MapData.COLS)
                    if (!MapData.isBasePassable(r, col))
                        c.drawRect(col * cw, r * ch, (col + 1) * cw, (r + 1) * ch, p)
            impassableBitmap = ib
            impassableBitmapBuilding = false
            post { invalidate() }
        }.start()
    }

    // ── Загрузка bitmap подложки ──────────────────────────────────────────────

    /**
     * Загружает растровую подложку карты из ресурсов с автоматическим уменьшением (inSampleSize).
     * Если изображение > 2048 пикселей по любой стороне — сжимает вдвое до укладки в лимит.
     * Использует RGB_565 для экономии памяти (2 байта/пиксель вместо 4).
     */
    private fun loadMapBitmap() {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(resources, R.drawable.campus_map, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                var ss = 1
                while (opts.outWidth / ss > 2048 || opts.outHeight / ss > 2048) ss *= 2
                mapBitmap = BitmapFactory.decodeResource(resources,
                    R.drawable.campus_map,
                    BitmapFactory.Options().apply { inSampleSize = ss; inPreferredConfig = Bitmap.Config.RGB_565 })
            }
        } catch (_: Exception) { } catch (_: OutOfMemoryError) { }
    }

    // ── Размер и матрица ──────────────────────────────────────────────────────

    /**
     * Вызывается при изменении размера View (поворот экрана, первый layout-проход).
     * Перезадаёт матрицу трансформации под новый размер и при необходимости
     * запускает построение impassableBitmap.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrixToFit(w, h)
        if (showImpassable && impassableBitmap == null) buildImpassableBitmapAsync()
    }

    /**
     * Сбрасывает матрицу так, чтобы изображение карты занимало максимальное место
     * в View без выхода за его границы (aspect-fit), с центрированием.
     * Пересчитывает invertMatrix для последующего перевода координат.
     */
    private fun resetMatrixToFit(vw: Int, vh: Int) {
        matrix.reset()
        val bmp = mapBitmap
        if (bmp != null) {
            val s = minOf(vw / bmp.width.toFloat(), vh / bmp.height.toFloat())
            matrix.setScale(s, s)
            matrix.postTranslate((vw - bmp.width * s) / 2f, (vh - bmp.height * s) / 2f)
        } else {
            val s = minOf(vw.toFloat() / MapData.COLS, vh.toFloat() / MapData.ROWS)
            matrix.setScale(s, s)
        }
        matrix.invert(invertMatrix)
        onMatrixReset?.invoke()
    }

    // ── Вспомогательные вычисления ────────────────────────────────────────────

    /** Ширина одной клетки в пикселях bitmap-пространства. */
    private fun getCellW(): Float = (mapBitmap?.width?.toFloat() ?: MapData.COLS.toFloat()) / MapData.COLS

    /** Высота одной клетки в пикселях bitmap-пространства. */
    private fun getCellH(): Float = (mapBitmap?.height?.toFloat() ?: MapData.ROWS.toFloat()) / MapData.ROWS

    /**
     * Возвращает прямоугольник видимой области в координатах клеток.
     * Преобразует углы экрана через invertMatrix в bitmap-координаты
     * и делит на размер клетки. Ограничивает результат размерами карты.
     * Используется для отрисовки только видимых клеток — оптимизация производительности.
     */
    private fun getVisibleRect(cw: Float, ch: Float): Rect {
        val pts = floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        invertMatrix.mapPoints(pts)
        return Rect(
            ((pts[0] / cw).toInt() - 1).coerceIn(0, MapData.COLS - 1),
            ((pts[1] / ch).toInt() - 1).coerceIn(0, MapData.ROWS - 1),
            ((pts[2] / cw).toInt() + 1).coerceIn(0, MapData.COLS - 1),
            ((pts[3] / ch).toInt() + 1).coerceIn(0, MapData.ROWS - 1)
        )
    }

    // ── Отрисовка ─────────────────────────────────────────────────────────────

    /**
     * Главный метод отрисовки View. Вызывается системой при каждом invalidate().
     *
     * Порядок слоёв (снизу вверх):
     *  1. Растровая подложка карты.
     *  2. Слой непроходимых клеток (impassableBitmap).
     *  3. Изменённые пользователем клетки (зелёные проходы / красные препятствия).
     *  4. Посещённые A* клетки (голубые).
     *  5. Путь A* (жёлтые клетки).
     *  6. Сетка клеток (если включена).
     *  7. Зоны кластеров (blob-заливка через saveLayer).
     *  8. Круги точек питания (большой + маленький центр).
     *  9. Кольца-подсветки результатов поиска по блюдам.
     * 10. Полилиния маршрута + номера точек.
     * 11. Маркеры старта и финиша пути.
     * 12. Overlay: кнопка сброса кластеров (в экранных координатах — вне canvas.save/restore).
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(matrix)
        val cw = getCellW(); val ch = getCellH()

        // 1. Подложка карты
        mapBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
            ?: canvas.drawColor(Color.rgb(200, 220, 200))

        val vis = getVisibleRect(cw, ch)

        // 2. Слой непроходимых клеток (предварительно собранный bitmap)
        if (showImpassable) {
            // Используем предварительно отрисованный bitmap (строится в фоне при первом включении).
            // Когда bitmap ещё не готов — ничего не рисуем; после готовности вызывается invalidate().
            impassableBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        }

        // 3. Изменённые пользователем клетки
        for (r in vis.top..vis.bottom) for (c in vis.left..vis.right)
            if (MapData.isModified(r, c))
                canvas.drawRect(c * cw, r * ch, (c + 1) * cw, (r + 1) * ch,
                    if (MapData.isPassable(r, c)) passageModPaint else obstacleModPaint)

        // 4–5. Посещённые A* клетки и путь
        // closed (голубой) и frontier (оранжевый) — накапливаются в startPathAnimation.
        if (animEvents.isNotEmpty()) {
            for (cell in animClosedCells)
                canvas.drawRect(cell.col * cw, cell.row * ch, (cell.col + 1) * cw, (cell.row + 1) * ch, visitedPaint)
            for (cell in animFrontierCells)
                canvas.drawRect(cell.col * cw, cell.row * ch, (cell.col + 1) * cw, (cell.row + 1) * ch, frontierPaint)
        }
        for (cell in currentPath) canvas.drawRect(cell.col * cw, cell.row * ch, (cell.col + 1) * cw, (cell.row + 1) * ch, pathPaint)

        // 6. Сетка клеток
        if (showGrid) {
            for (c in vis.left..vis.right + 1) { val x = c * cw; canvas.drawLine(x, vis.top * ch, x, (vis.bottom + 1) * ch, gridPaint) }
            for (r in vis.top..vis.bottom + 1) { val y = r * ch; canvas.drawLine(vis.left * cw, y, (vis.right + 1) * cw, y, gridPaint) }
        }

        // 7. Зоны кластеров
        if (showClusters && clusterAssignments.isNotEmpty()) {
            drawClusterZones(canvas, cw, ch)
        }

        // 8. Круги точек питания
        val cellMin = minOf(cw, ch)
        val foodVisualRadius = cellMin * 5f
        val foodDotRadius = cellMin * 0.45f
        for (fp in MapData.getFoodPoints()) {
            val cx = (fp.col + 0.5f) * cw; val cy = (fp.row + 0.5f) * ch
            val clusterId = clusterIdByCell[fp.row * MapData.COLS + fp.col]
            val isSingleton = clusterId != null && clusterId in clusterSingletonIds
            if (clusterId != null && !isSingleton) {
                // После кластеризации — цвет совпадает с цветом кластера;
                // при clusterId ≥ размера палитры — берём цвет по модулю (палитра повторяется)
                val baseColor = clusterColors[clusterId % clusterColors.size]
                foodFillPaint.color = Color.argb(70, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                foodBorderPaint.color = Color.argb(200, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                foodDotPaint.color = Color.argb(230, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                foodDotBorderPaint.color = baseColor
            } else {
                // До кластеризации или одиночка-аутлайер — оранжевый
                foodFillPaint.color = Color.argb(70, 255, 165, 0)
                foodBorderPaint.color = Color.argb(200, 180, 90, 0)
                foodDotPaint.color = Color.argb(230, 255, 140, 0)
                foodDotBorderPaint.color = Color.argb(255, 120, 60, 0)
            }
            canvas.drawCircle(cx, cy, foodVisualRadius, foodFillPaint)
            canvas.drawCircle(cx, cy, foodVisualRadius, foodBorderPaint)
            canvas.drawCircle(cx, cy, foodDotRadius, foodDotPaint)
            canvas.drawCircle(cx, cy, foodDotRadius, foodDotBorderPaint)
        }

        // 9. Достопримечательности — зелёные кружки, на треть меньше точек питания
        val lmVisualRadius = cellMin * 3.33f
        val lmDotRadius    = cellMin * 0.3f
        for (lm in MapData.getLandmarks()) {
            val cx = (lm.col + 0.5f) * cw; val cy = (lm.row + 0.5f) * ch
            canvas.drawCircle(cx, cy, lmVisualRadius, landmarkFillPaint)
            canvas.drawCircle(cx, cy, lmVisualRadius, landmarkBorderPaint)
            canvas.drawCircle(cx, cy, lmDotRadius,    landmarkDotPaint)
            canvas.drawCircle(cx, cy, lmDotRadius,    landmarkDotBorderPaint)
        }

        // 10. ACO маршрут по достопримечательностям
        if (landmarkAStarPath.isNotEmpty()) {
            for (cell in landmarkAStarPath)
                canvas.drawRect(cell.col * cw, cell.row * ch, (cell.col + 1) * cw, (cell.row + 1) * ch, landmarkPathPaint)
        }
        if (routeLandmarks.isNotEmpty()) {
            val nr = cellMin * 0.75f
            lmRouteNumTextPaint.textSize = nr * 1.1f
            routeLandmarks.forEachIndexed { idx, lm ->
                val cx = (lm.col + 0.5f) * cw; val cy = (lm.row + 0.5f) * ch
                canvas.drawCircle(cx, cy, nr, lmRouteNumBgPaint)
                canvas.drawText("${idx + 1}", cx, cy + nr * 0.38f, lmRouteNumTextPaint)
            }
        }

        // 11. Кольца подсветки результатов поиска по блюдам
        val highlightRadius = cellMin * 5f + 10f
        for ((hr, hc) in highlightedFoodPoints) {
            val hcx = (hc + 0.5f) * cw; val hcy = (hr + 0.5f) * ch
            canvas.drawCircle(hcx, hcy, highlightRadius, highlightRingPaint)
        }

        if (routeAStarPath.isNotEmpty()) {
            for (cell in routeAStarPath)
                canvas.drawRect(cell.col * cw, cell.row * ch, (cell.col + 1) * cw, (cell.row + 1) * ch, pathPaint)
        } else if (routeFoodPoints.size >= 2) {
            startCell?.let { sc ->
                val fp = routeFoodPoints[0]
                canvas.drawLine((sc.col + 0.5f) * cw, (sc.row + 0.5f) * ch,
                    (fp.col + 0.5f) * cw, (fp.row + 0.5f) * ch, routeLinePaint)
            }
            for (i in 0 until routeFoodPoints.size - 1) {
                val a = routeFoodPoints[i]; val b = routeFoodPoints[i + 1]
                canvas.drawLine((a.col + 0.5f) * cw, (a.row + 0.5f) * ch,
                    (b.col + 0.5f) * cw, (b.row + 0.5f) * ch, routeLinePaint)
            }
        }
        if (routeFoodPoints.isNotEmpty()) {
            val nr = cellMin * 0.85f
            routeNumTextPaint.textSize = nr * 1.1f
            routeFoodPoints.forEachIndexed { idx, fp ->
                val cx = (fp.col + 0.5f) * cw; val cy = (fp.row + 0.5f) * ch
                canvas.drawCircle(cx, cy, nr, routeNumBgPaint)
                canvas.drawText("${idx + 1}", cx, cy + nr * 0.38f, routeNumTextPaint)
            }
        }

        // 11. Маркеры старта и финиша
        startCell?.let { drawMarker(canvas, it, startPaint, cw, ch) }
        endCell?.let { drawMarker(canvas, it, endPaint, cw, ch) }
        canvas.restore()

        // 12. Overlay-кнопка в экранных координатах (поверх трансформации)
        if (showClusters) {
            drawResetClustersButton(canvas)
        }
    }

    // ── Вспомогательные методы отрисовки ─────────────────────────────────────

    /**
     * Рисует кнопку «✕ кластеры» в экранных координатах (без matrix).
     * Позиция: вертикально по центру экрана, у левого края.
     * Сохраняет прямоугольник в [resetBtnRect] для hit-test в onTouchEvent.
     */
    private fun drawResetClustersButton(canvas: Canvas) {
        val btnTop = height / 2f - resetBtnH / 2f
        resetBtnRect.set(resetBtnLeft, btnTop, resetBtnLeft + resetBtnW, btnTop + resetBtnH)
        val radius = 8f * density
        canvas.drawRoundRect(resetBtnRect, radius, radius, resetBtnBgPaint)
        canvas.drawRoundRect(resetBtnRect, radius, radius, resetBtnBorderPaint)
        canvas.drawText(
            resetBtnText,
            resetBtnRect.left + resetBtnPadH,
            resetBtnRect.top + resetBtnPadV + resetBtnTextBounds.height(),
            resetBtnTextPaint
        )
    }

    /**
     * Рисует зоны кластеров как «пятна» (blob) — наложение кругов одного цвета
     * через saveLayer с общей прозрачностью [CLUSTER_ZONE_ALPHA].
     * Использует минимальный ограничивающий прямоугольник + радиус пятна для
     * задания области saveLayer, избегая полного-экранного слоя.
     *
     * @param canvas Canvas в bitmap-пространстве (трансформация уже применена).
     * @param cw     Ширина клетки в пикселях bitmap.
     * @param ch     Высота клетки в пикселях bitmap.
     */
    // Пересобирает группы при изменении clusterAssignments
    private fun rebuildClusterGroups() {
        clusterGroups.clear()
        for ((pt, id) in clusterAssignments)
            clusterGroups.getOrPut(id) { mutableListOf() }.add(pt)
    }

    private fun drawClusterZones(canvas: Canvas, cw: Float, ch: Float) {
        val groups = clusterGroups

        val blobRadius = maxOf(cw, ch) * 3.6f
        clusterLayerPaint.alpha = CLUSTER_ZONE_ALPHA

        for (id in groups.keys.sorted()) {
            // Одиночки-аутлайеры — без зоны (рисуются оранжевым кружком выше в onDraw)
            if (id in clusterSingletonIds) continue
            val points = groups[id] ?: continue
            val color = clusterColors[id % clusterColors.size]
            val l = points.minOf { it.second } * cw - blobRadius
            val t = points.minOf { it.first } * ch - blobRadius
            val r = points.maxOf { it.second + 1 } * cw + blobRadius
            val b = points.maxOf { it.first + 1 } * ch + blobRadius
            canvas.saveLayer(RectF(l, t, r, b), clusterLayerPaint)
            clusterFillPaint.color = color
            for (pt in points) {
                canvas.drawCircle((pt.second + 0.5f) * cw, (pt.first + 0.5f) * ch, blobRadius, clusterFillPaint)
            }
            canvas.restore()
        }
    }

    /**
     * Рисует круглый маркер (начальная/конечная точка пути) с белой обводкой.
     *
     * @param paint Заливка маркера (зелёный для старта, красный для финиша).
     */
    private fun drawMarker(canvas: Canvas, cell: PathFinder.Cell, paint: Paint, cw: Float, ch: Float) {
        val cx = (cell.col + 0.5f) * cw; val cy = (cell.row + 0.5f) * ch
        val r = minOf(cw, ch) * 0.45f
        canvas.drawCircle(cx, cy, r, paint); canvas.drawCircle(cx, cy, r, markerBorderPaint)
    }

    // ── Обработка касаний ─────────────────────────────────────────────────────

    /**
     * Обрабатывает все жесты касания:
     *  - ACTION_DOWN: запоминает точку начала для детектирования тапа и перетаскивания.
     *  - ACTION_POINTER_DOWN: активирует multitouch-режим, фиксирует расстояние между пальцами.
     *  - ACTION_MOVE: перетаскивание одним пальцем (postTranslate) или масштабирование двумя
     *    (postScale вокруг середины пальцев + postTranslate для pan).
     *  - ACTION_UP: если смещение < TAP_THRESHOLD — обрабатывает тап.
     *  - ACTION_POINTER_UP: завершает multitouch, сохраняет позицию оставшегося пальца.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isPaintMode = editMode == EditMode.ADD_OBSTACLE || editMode == EditMode.ADD_PASSAGE
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x; touchDownY = event.y
                lastTouchX = event.x; lastTouchY = event.y
                isMultiTouch = false; isPainting = false; lastPaintedCell = null
                // В режиме рисования сразу закрашиваем первую клетку
                if (isPaintMode) paintCellAt(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                isMultiTouch = true; lastPointerDist = pointerDist(event)
                midPointX = (event.getX(0) + event.getX(1)) / 2f
                midPointY = (event.getY(0) + event.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2 && isMultiTouch) {
                    val nd = pointerDist(event)
                    if (nd > 0 && lastPointerDist > 0) {
                        val mx = (event.getX(0) + event.getX(1)) / 2f
                        val my = (event.getY(0) + event.getY(1)) / 2f
                        matrix.postScale(nd / lastPointerDist, nd / lastPointerDist, mx, my)
                        matrix.postTranslate(mx - midPointX, my - midPointY)
                        matrix.invert(invertMatrix); midPointX = mx; midPointY = my; invalidate()
                    }
                    lastPointerDist = nd
                } else if (!isMultiTouch) {
                    if (isPaintMode) {
                        // Рисуем мазок — закрашиваем каждую новую клетку под пальцем
                        paintCellAt(event.x, event.y)
                        isPainting = true
                    } else {
                        matrix.postTranslate(event.x - lastTouchX, event.y - lastTouchY)
                        matrix.invert(invertMatrix); invalidate()
                    }
                    lastTouchX = event.x; lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                // В режиме рисования drag-мазок уже закрасил всё нужное;
                // handleTap вызываем только если палец не двигался (чистый тап)
                val isTap = Math.abs(event.x - touchDownX) < TAP_THRESHOLD &&
                            Math.abs(event.y - touchDownY) < TAP_THRESHOLD
                if (!isMultiTouch && isTap && !isPaintMode) {
                    if (showClusters && resetBtnRect.contains(event.x, event.y)) {
                        onResetClusters?.invoke()
                    } else {
                        handleTap(event.x, event.y)
                    }
                } else if (!isMultiTouch && isTap && isPaintMode && !isPainting) {
                    // Тап в режиме рисования без движения — обрабатываем как одиночный тап
                    handleTap(event.x, event.y)
                }
                isPainting = false; lastPaintedCell = null; isMultiTouch = false
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) {
                isMultiTouch = false
                val idx = if (event.actionIndex == 0) 1 else 0
                lastTouchX = event.getX(idx); lastTouchY = event.getY(idx)
            }
        }
        return true
    }

    /**
     * Запускает пошаговую анимацию обхода A* на основе событийного потока.
     *
     * Алгоритм:
     *  1. Сбрасывает предыдущую анимацию и текущий путь.
     *  2. Вычисляет batchSize автоматически по расстоянию [distanceHint]:
     *     чем дальше старт от финиша, тем больше событий обрабатывается за кадр
     *     (анимация ускоряется, чтобы не затягиваться на длинных маршрутах).
     *     Дополнительно ограничивает общее время анимации 5 секундами.
     *  3. Каждые [ANIM_DELAY_MS] мс обрабатывает batchSize событий:
     *     OPEN-событие — добавляет ячейку в [animFrontierCells] (оранжевый);
     *     CLOSE-событие — перемещает ячейку из frontier в [animClosedCells] (голубой).
     *  4. По завершении всех событий делает паузу [ANIM_PATH_DELAY_MS]
     *     и отображает итоговый путь.
     *
     * @param animEvents   Массив OPEN/CLOSE событий из PathResult.animEvents.
     * @param finalPath    Итоговый путь, показывается после анимации.
     * @param distanceHint Октильное расстояние старт→финиш (управляет скоростью).
     */
    fun startPathAnimation(
        animEvents: IntArray,
        finalPath: List<PathFinder.Cell>,
        distanceHint: Float
    ) {
        cancelAnimation()
        currentPath = emptyList()

        this.animEvents = animEvents
        animFinalPath   = finalPath
        animEventIdx    = 0

        if (animEvents.isEmpty()) {
            currentPath = finalPath
            return
        }

        // Скорость анимации масштабируется по расстоянию:
        //   targetMs = расстояние * 4, зажато в [800, 5000] мс
        // Это гарантирует короткую анимацию для ближних точек и не даёт
        // растянуть её сверх 5 секунд при пересечении всего кампуса.
        val targetMs   = (distanceHint * 4f).toLong().coerceIn(800L, 5000L)
        val totalFrames = (targetMs / ANIM_DELAY_MS).toInt().coerceAtLeast(1)
        val batchSize  = maxOf(1, animEvents.size / totalFrames)

        val cols = MapData.COLS
        animRunnable = object : Runnable {
            override fun run() {
                val end = minOf(animEventIdx + batchSize, animEvents.size)
                while (animEventIdx < end) {
                    val event = animEvents[animEventIdx++]
                    if (event >= 0) {
                        // OPEN: ячейка впервые добавлена в открытый список (frontier)
                        animFrontierCells.add(PathFinder.Cell(event / cols, event % cols))
                    } else {
                        // CLOSE: ячейка перешла в закрытый список
                        val idx  = -event - 1
                        val cell = PathFinder.Cell(idx / cols, idx % cols)
                        animFrontierCells.remove(cell)
                        animClosedCells.add(cell)
                    }
                }
                invalidate()
                if (animEventIdx < animEvents.size) {
                    animHandler.postDelayed(this, ANIM_DELAY_MS)
                } else {
                    // Небольшая пауза перед показом итогового пути
                    animHandler.postDelayed({
                        currentPath  = animFinalPath
                        animRunnable = null
                        invalidate()
                    }, ANIM_PATH_DELAY_MS)
                }
            }
        }
        animHandler.post(animRunnable!!)
    }

    /**
     * Останавливает текущую анимацию A* и сбрасывает её состояние.
     * Вызывается при повторном поиске, очистке или смене режима.
     */
    fun cancelAnimation() {
        animRunnable?.let { animHandler.removeCallbacks(it) }
        animRunnable = null
        animEvents   = IntArray(0)
        animEventIdx = 0
        animFrontierCells.clear()
        animClosedCells.clear()
    }

    /**
     * Применяет масштабирование из центра View (используется SeekBar в MainActivity).
     * Пересчитывает invertMatrix и вызывает перерисовку.
     *
     * @param factor Коэффициент масштабирования (>1 = увеличение, <1 = уменьшение).
     */
    fun applyZoomFromCenter(factor: Float) {
        matrix.postScale(factor, factor, width / 2f, height / 2f)
        matrix.invert(invertMatrix)
        invalidate()
    }

    /**
     * Перемещает камеру так, чтобы клетка (row, col) оказалась в центре экрана.
     * Текущий масштаб не меняется — только трансляция.
     * Вызывается при выборе заведения из списка.
     *
     * @param row Строка клетки на карте.
     * @param col Столбец клетки на карте.
     */
    fun centerOnCell(row: Int, col: Int) {
        val cw = getCellW()
        val ch = getCellH()
        // Центр клетки в bitmap-пространстве
        val pts = floatArrayOf((col + 0.5f) * cw, (row + 0.5f) * ch)
        // Переводим в экранные координаты текущей матрицей
        matrix.mapPoints(pts)
        // Смещаем так чтобы точка попала в центр View
        matrix.postTranslate(width / 2f - pts[0], height / 2f - pts[1])
        matrix.invert(invertMatrix)
        invalidate()
    }

    // ── Рисование мазком ─────────────────────────────────────────────────────

    /**
     * Закрашивает клетку под экранными координатами (sx, sy) в режиме
     * ADD_OBSTACLE (делает непроходимой) или ADD_PASSAGE (делает проходимой).
     * Повторные вызовы для одной и той же клетки пропускаются — [lastPaintedCell]
     * гарантирует, что каждая клетка меняется ровно один раз за мазок.
     */
    private fun paintCellAt(sx: Float, sy: Float) {
        val pts = floatArrayOf(sx, sy); invertMatrix.mapPoints(pts)
        val cw  = getCellW(); val ch = getCellH()
        val col = (pts[0] / cw).toInt(); val row = (pts[1] / ch).toInt()
        if (!MapData.isInBounds(row, col)) return
        val cell = Pair(row, col)
        if (cell == lastPaintedCell) return      // уже обработали эту клетку
        lastPaintedCell = cell
        when (editMode) {
            EditMode.ADD_OBSTACLE -> MapData.setObstacle(row, col)
            EditMode.ADD_PASSAGE  -> MapData.setPassage(row, col)
            else -> return
        }
        invalidate()
    }

    // ── Логика тапа ───────────────────────────────────────────────────────────

    /**
     * Обрабатывает одиночный тап по карте.
     *
     * 1. Переводит экранные координаты в bitmap-координаты через invertMatrix.
     * 2. Делит на размер клетки для получения (row, col).
     * 3. В зависимости от editMode:
     *    - ADD_OBSTACLE/ADD_PASSAGE: переключает проходимость клетки.
     *    - ADD_FOOD_POINT: добавляет или убирает точку питания, вызывает onFoodPointToggled.
     *    - NONE: сначала проверяет попадание в круг точки питания (радиус 5 клеток),
     *            затем вызывает onCellTapped с координатами точки питания или обычной клетки.
     *
     * @param sx Экранная X-координата тапа.
     * @param sy Экранная Y-координата тапа.
     */
    private fun handleTap(sx: Float, sy: Float) {
        val pts = floatArrayOf(sx, sy); invertMatrix.mapPoints(pts)
        val bx = pts[0]; val by = pts[1]
        val cw = getCellW(); val ch = getCellH()
        val col = (bx / cw).toInt(); val row = (by / ch).toInt()
        if (!MapData.isInBounds(row, col)) return
        when (editMode) {
            EditMode.ADD_OBSTACLE, EditMode.ADD_PASSAGE -> {
                if (MapData.isPassable(row, col)) MapData.setObstacle(row, col) else MapData.setPassage(row, col); invalidate()
            }
            EditMode.ADD_FOOD_POINT -> {
                val existed = MapData.getFoodPointAt(row, col) != null
                if (existed) MapData.removeFoodPoint(row, col) else MapData.addFoodPoint(row, col)
                onFoodPointToggled?.invoke(row, col, !existed)
                invalidate()
            }
            EditMode.ADD_LANDMARK -> {
                if (MapData.getLandmarkAt(row, col) != null) MapData.removeLandmark(row, col)
                else MapData.addLandmark(row, col)
                invalidate()
                onLandmarkChanged?.invoke()
            }
            EditMode.NONE -> {
                // Проверяем попадание в круг точки питания (радиус 5 клеток)
                val foodRadius = minOf(cw, ch) * 5f
                val hitFp = MapData.getFoodPoints().firstOrNull { fp ->
                    val fcx = (fp.col + 0.5f) * cw; val fcy = (fp.row + 0.5f) * ch
                    val dx = bx - fcx; val dy = by - fcy
                    dx * dx + dy * dy <= foodRadius * foodRadius
                }
                if (hitFp != null) {
                    onCellTapped?.invoke(hitFp.row, hitFp.col)
                    return
                }
                // Проверяем попадание в круг достопримечательности (радиус 3.33 клетки)
                val lmRadius = minOf(cw, ch) * 3.33f
                val hitLm = MapData.getLandmarks().firstOrNull { lm ->
                    val lcx = (lm.col + 0.5f) * cw; val lcy = (lm.row + 0.5f) * ch
                    val dx = bx - lcx; val dy = by - lcy
                    dx * dx + dy * dy <= lmRadius * lmRadius
                }
                if (hitLm != null) {
                    onLandmarkTapped?.invoke(hitLm.row, hitLm.col)
                } else {
                    onCellTapped?.invoke(row, col)
                }
            }
        }
    }

    /**
     * Вычисляет расстояние между двумя точками касания (для определения масштаба при щипке).
     *
     * @param e MotionEvent с как минимум двумя активными указателями.
     */
    private fun pointerDist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1); val dy = e.getY(0) - e.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }
}
