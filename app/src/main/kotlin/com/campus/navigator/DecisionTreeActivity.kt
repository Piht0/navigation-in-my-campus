package com.campus.navigator

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout

/**
 * Экран "Дерево решений" — рекомендация заведения по алгоритму ID3.
 *
 * Вкладка 1 «Обучение»:
 *   - EditText для вставки обучающей выборки в формате CSV
 *   - Кнопка «Образец» — загружает decision_tree_sample.csv из assets
 *   - Кнопка «Обучить» — парсит CSV, строит ID3-дерево, показывает статус
 *   - WebView — HTML-визуализация построенного дерева
 *
 * Вкладка 2 «Найти место»:
 *   - Спиннеры для всех 6 признаков (значения берутся из обучающей выборки)
 *   - Кнопка «Найти» — прогоняет выбранные признаки по дереву
 *   - Карточка с результатом (название заведения)
 *   - Список шагов пути по узлам дерева
 */
class DecisionTreeActivity : AppCompatActivity() {

    private lateinit var vm: DecisionTreeViewModel

    // Views — общие
    private lateinit var tabLayout:    TabLayout
    private lateinit var panelTrain:   View
    private lateinit var panelFind:    View

    // Вкладка 1
    private lateinit var etCsv:        EditText
    private lateinit var btnLoadSample:Button
    private lateinit var btnTrain:     Button
    private lateinit var tvStatus:     TextView
    private lateinit var webViewTree:  WebView

    // Вкладка 2
    private lateinit var spinnerLocation: Spinner
    private lateinit var spinnerBudget:   Spinner
    private lateinit var spinnerTime:     Spinner
    private lateinit var spinnerFood:     Spinner
    private lateinit var spinnerQueue:    Spinner
    private lateinit var spinnerWeather:  Spinner
    private lateinit var btnFind:         Button
    private lateinit var cardResult:      View
    private lateinit var tvResult:        TextView
    private lateinit var cardPath:        View
    private lateinit var pathContainer:   LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decision_tree)

        vm = DecisionTreeViewModel(this)

        findViewById<android.widget.TextView>(R.id.btnCloseTree).setOnClickListener { finish() }

        bindViews()
        setupTabs()
        setupTrainTab()
        setupFindTab()

        // Если дерево уже загружено (из сохранённого датасета) — обновляем UI
        if (vm.tree != null) {
            tvStatus.text = "Дерево загружено из памяти (${vm.samples.size} строк, глубина ${ID3Algorithm.depth(vm.tree!!)})"
            refreshTreeVisualization()
            refreshSpinners()
        }
    }

    // ── Привязка Views ────────────────────────────────────────────────────────

    private fun bindViews() {
        tabLayout       = findViewById(R.id.tabLayout)
        panelTrain      = findViewById(R.id.panelTrain)
        panelFind       = findViewById(R.id.panelFind)

        etCsv           = findViewById(R.id.etCsv)
        btnLoadSample   = findViewById(R.id.btnLoadSample)
        btnTrain        = findViewById(R.id.btnTrain)
        tvStatus        = findViewById(R.id.tvTrainStatus)
        webViewTree     = findViewById(R.id.webViewTree)

        spinnerLocation = findViewById(R.id.spinnerLocation)
        spinnerBudget   = findViewById(R.id.spinnerBudget)
        spinnerTime     = findViewById(R.id.spinnerTime)
        spinnerFood     = findViewById(R.id.spinnerFoodType)
        spinnerQueue    = findViewById(R.id.spinnerQueue)
        spinnerWeather  = findViewById(R.id.spinnerWeather)
        btnFind         = findViewById(R.id.btnFind)
        cardResult      = findViewById(R.id.cardResult)
        tvResult        = findViewById(R.id.tvResult)
        cardPath        = findViewById(R.id.cardPath)
        pathContainer   = findViewById(R.id.pathContainer)
    }

    // ── Вкладки ───────────────────────────────────────────────────────────────

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Обучение"))
        tabLayout.addTab(tabLayout.newTab().setText("Найти место"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                panelTrain.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                panelFind.visibility  = if (tab.position == 1) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // ── Вкладка 1: Обучение ───────────────────────────────────────────────────

    private fun setupTrainTab() {
        webViewTree.settings.javaScriptEnabled = false
        webViewTree.setBackgroundColor(Color.parseColor("#F2F4F7"))

        btnLoadSample.setOnClickListener {
            etCsv.setText(vm.getSavedCsv())
        }

        btnTrain.setOnClickListener {
            val csv = etCsv.text.toString().trim()
            if (csv.isBlank()) { tvStatus.text = "Вставьте CSV в поле выше."; return@setOnClickListener }

            tvStatus.text = "Обучение..."
            Thread {
                val status = vm.loadAndTrain(csv)
                runOnUiThread {
                    tvStatus.text = status
                    refreshTreeVisualization()
                    refreshSpinners()
                }
            }.start()
        }

        // Загружаем образец в EditText при старте, если поле пустое
        if (etCsv.text.isBlank()) {
            etCsv.setText(vm.getSavedCsv())
        }
    }

    private fun refreshTreeVisualization() {
        val html = vm.generateTreeHtml()
        webViewTree.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // ── Вкладка 2: Найти место ────────────────────────────────────────────────

    private fun setupFindTab() {
        refreshSpinners()

        btnFind.setOnClickListener {
            if (vm.tree == null) {
                Toast.makeText(this, "Сначала обучите дерево на вкладке «Обучение»", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val input = mapOf(
                "location"        to spinnerLocation.selectedItem.toString(),
                "budget"          to spinnerBudget.selectedItem.toString(),
                "time_available"  to spinnerTime.selectedItem.toString(),
                "food_type"       to spinnerFood.selectedItem.toString(),
                "queue_tolerance" to spinnerQueue.selectedItem.toString(),
                "weather"         to spinnerWeather.selectedItem.toString()
            )

            val (place, path) = vm.predict(input) ?: return@setOnClickListener

            // Результат
            tvResult.text = place.replace("_", " ").replaceFirstChar { it.uppercase() }
            cardResult.visibility = View.VISIBLE

            // Путь по дереву
            pathContainer.removeAllViews()
            path.forEachIndexed { idx, step ->
                val tv = TextView(this).apply {
                    text = "  ${idx + 1}. $step"
                    textSize = 13f
                    setTextColor(Color.parseColor("#1C1C1E"))
                    setPadding(0, 4, 0, 4)
                    if (idx < path.size - 1) {
                        // Стрелка вниз между шагами
                    }
                }
                pathContainer.addView(tv)

                if (idx < path.size - 1) {
                    val arrow = TextView(this).apply {
                        text = "     ↓"
                        textSize = 11f
                        setTextColor(Color.parseColor("#0057A8"))
                    }
                    pathContainer.addView(arrow)
                }
            }

            // Итоговый лист
            val leaf = TextView(this).apply {
                text = "  → ${tvResult.text}"
                textSize = 14f
                setTextColor(Color.parseColor("#0057A8"))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 8, 0, 0)
            }
            pathContainer.addView(leaf)

            cardPath.visibility = View.VISIBLE
        }
    }

    private fun refreshSpinners() {
        val spinners = mapOf(
            spinnerLocation to "location",
            spinnerBudget   to "budget",
            spinnerTime     to "time_available",
            spinnerFood     to "food_type",
            spinnerQueue    to "queue_tolerance",
            spinnerWeather  to "weather"
        )
        spinners.forEach { (spinner, feature) ->
            val values = vm.valuesFor(feature)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, values).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }
    }
}
