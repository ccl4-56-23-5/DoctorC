package com.wirelessalien.zipxtract.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.helper.AppEvent
import com.wirelessalien.zipxtract.helper.EventBus
import com.wirelessalien.zipxtract.nativeui.ActiveOperation
import com.wirelessalien.zipxtract.nativeui.Art
import com.wirelessalien.zipxtract.nativeui.CatalogFileWalker
import com.wirelessalien.zipxtract.nativeui.CategoryCatalog
import com.wirelessalien.zipxtract.nativeui.ClockRenameIconView
import com.wirelessalien.zipxtract.nativeui.PhotoTimeSync
import com.wirelessalien.zipxtract.nativeui.CreationOptions
import com.wirelessalien.zipxtract.nativeui.DockGeometry
import com.wirelessalien.zipxtract.nativeui.DragSelectionController
import com.wirelessalien.zipxtract.nativeui.GlassMaterial
import com.wirelessalien.zipxtract.nativeui.LiquidGlassStyle
import com.wirelessalien.zipxtract.nativeui.LiquidGlassView
import com.wirelessalien.zipxtract.nativeui.accessible
import com.wirelessalien.zipxtract.nativeui.dp
import com.wirelessalien.zipxtract.nativeui.fileMimeType
import com.wirelessalien.zipxtract.nativeui.iconButton
import com.wirelessalien.zipxtract.nativeui.label
import com.wirelessalien.zipxtract.nativeui.menuAnchor
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Opaque file search with the browser's selection gestures and file actions. */
class SearchActivity : FileActionActivity() {
    private val catalog by lazy { CategoryCatalog(this) }
    private val glass by lazy { GlassMaterial(this) }
    private var pendingSearch: Job? = null
    private lateinit var field: EditText
    private lateinit var filters: ScrollView
    private lateinit var results: LinearLayout
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var root: LinearLayout
    private lateinit var searchToolbar: LinearLayout
    private lateinit var selectionToolbar: LinearLayout
    private lateinit var selectionTitle: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var dragSelection: DragSelectionController
    private lateinit var actionHost: FrameLayout
    private lateinit var actionDock: LiquidGlassView
    private lateinit var actionButtons: LinearLayout
    private lateinit var renameIcon: ClockRenameIconView
    private lateinit var taskBanner: LinearLayout
    private lateinit var taskText: TextView
    private val adapter by lazy { SearchRows() }
    private val selected = linkedSetOf<String>()
    private var selectionBack: OnBackInvokedCallback? = null
    private var resumed = false
    private var startedOperation = false
    private var type: String? = null
    private var source: String? = null
    private var submitted: String? = null
    private var showKeyboardOnFocus = false
    private val chips = mutableListOf<Pair<TextView, Boolean>>()
    private val roots by lazy { intent.getStringArrayListExtra(EXTRA_ROOTS).orEmpty().map(::File) }
    private val directory by lazy { intent.getStringExtra(EXTRA_DIRECTORY)?.let(::File) }
    private val includeHidden by lazy { intent.getBooleanExtra(EXTRA_HIDDEN, false) }

    override fun storageRoots() = roots

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        type = state?.getString("type")
        source = state?.getString("source")
        selected.addAll(state?.getStringArrayList("selected").orEmpty())
        startedOperation = state?.getBoolean("startedOperation") == true && ActiveOperation.busy()
        val restored = state?.getString("submitted")
        val pageColor = if (colors.dark) Color.BLACK else colors.page
        window.setBackgroundDrawableResource(if (colors.dark) android.R.color.black else android.R.color.white)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.search_page_in, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, R.anim.search_page_out)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor)
            isFocusableInTouchMode = true
        }
        searchToolbar = toolbar(state?.getString("input") ?: intent.getStringExtra(EXTRA_QUERY).orEmpty())
        selectionToolbar = selectionBar()
        root.addView(searchToolbar)
        root.addView(selectionToolbar)
        root.addView(transferPanel(), LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(16), 0, dp(16), 0)
        })
        val body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        filters = filterPanel()
        results = resultsPanel()
        body.addView(filters, FrameLayout.LayoutParams(-1, -1))
        body.addView(results, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            layoutActionDock()
            insets
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !colors.dark
            isAppearanceLightNavigationBars = !colors.dark
        }
        if (restored == null) {
            showKeyboardOnFocus = true
            field.requestFocus()
        } else submit(restored, refresh = true)
        renderSelection()
        observeFileEvents()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        renameIcon.setHostResumed(selected.isNotEmpty())
        updateTaskBanner()
    }

    override fun onPause() {
        resumed = false
        renameIcon.setHostResumed(false)
        dragSelection.cancelGesture()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !showKeyboardOnFocus) return
        showKeyboardOnFocus = false
        field.post {
            if (hasWindowFocus() && !isFinishing && submitted == null) {
                field.requestFocus()
                WindowCompat.getInsetsController(window, field).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun toolbar(input: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(8))
        val back = iconButton("back", "返回") { finish() }.apply {
            background = colors.background(if (colors.dark) 0xff141414.toInt() else 0xfff2f2f2.toInt(), dp(24).toFloat())
        }
        addView(back, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        val capsule = LinearLayout(this@SearchActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = colors.background(chipColor(), dp(26).toFloat())
            setPadding(dp(13), 0, dp(4), 0)
        }
        capsule.addView(ImageView(this@SearchActivity).apply {
            setImageDrawable(Art.drawable(context, "search"))
            imageAlpha = 142
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        field = EditText(this@SearchActivity).apply {
            tag = "native_search_field"
            hint = "搜索文件"
            textSize = 20f
            setTextColor(colors.text)
            setHintTextColor(if (colors.dark) 0xff999999.toInt() else 0xff64676e.toInt())
            background = null
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(10), 0, dp(4), 0)
            setText(input)
            setSelection(text.length)
            setOnEditorActionListener { _, id, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER
                if (id != EditorInfo.IME_ACTION_SEARCH && !enter) return@setOnEditorActionListener false
                if (event == null || event.action == KeyEvent.ACTION_UP) submit(text.toString())
                true
            }
        }
        capsule.addView(field, LinearLayout.LayoutParams(0, -1, 1f))
        val clear = iconButton("close", "清除搜索文字") { field.text.clear(); showFilters() }
        clear.visibility = if (input.isEmpty()) View.GONE else View.VISIBLE
        field.doAfterTextChanged { clear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }
        capsule.addView(clear, LinearLayout.LayoutParams(dp(44), dp(48)))
        addView(capsule, LinearLayout.LayoutParams(0, dp(48), 1f))
    }

    private fun selectionBar() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(8))
        visibility = View.GONE
        addView(iconButton("close", "取消选择") { clearSelection() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        selectionTitle = label("", 19f, colors.text, true)
        addView(selectionTitle, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        addView(iconButton("all", "全选") {
            dragSelection.cancelGesture()
            adapter.currentList.forEach { selected.add(it.file.path) }
            renderSelection()
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun filterPanel() = ScrollView(this).apply {
        val groups = LinearLayout(this@SearchActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(20))
            addView(group("按类型查找", TYPES, true))
            addView(group("按来源查找", SOURCES.keys.toList(), false),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(36) })
        }
        addView(groups, FrameLayout.LayoutParams(-1, -2))
        isVerticalScrollBarEnabled = false
    }

    private fun group(title: String, options: List<String>, byType: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 18f, if (colors.dark) 0xffa5b0c3.toInt() else 0xff566176.toInt()).apply {
            setPadding(dp(15), 0, 0, dp(13))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        val optionsView = LinearLayout(this@SearchActivity).apply { orientation = LinearLayout.VERTICAL }
        val buttons = options.map { option ->
            label(option, 18f, colors.text).apply {
                gravity = Gravity.CENTER
                minimumWidth = dp(82)
                minimumHeight = dp(44)
                setPadding(dp(21), dp(8), dp(21), dp(8))
                setOnClickListener {
                    if (byType) type = option.takeUnless { type == it }
                    else source = option.takeUnless { source == it }
                    submit(field.text.toString())
                }
                chips += this to byType
                styleChip(this, byType)
            }
        }
        addView(optionsView, LinearLayout.LayoutParams(-1, -2))
        optionsView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width > 0 && width != oldRight - oldLeft) packChips(optionsView, buttons, width)
        }
    }

    private fun packChips(container: LinearLayout, buttons: List<TextView>, width: Int) {
        for (i in 0 until container.childCount) (container.getChildAt(i) as LinearLayout).removeAllViews()
        container.removeAllViews()
        var row = LinearLayout(this)
        container.addView(row, LinearLayout.LayoutParams(-1, -2))
        var used = 0
        for (button in buttons) {
            button.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val cell = button.measuredWidth
            if (used > 0 && used + dp(12) + cell > width) {
                row = LinearLayout(this)
                container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                used = 0
            }
            val gap = if (used == 0) 0 else dp(12)
            row.addView(button, LinearLayout.LayoutParams(cell, -2).apply { leftMargin = gap })
            used += gap + cell
        }
    }

    private fun chipColor() = if (colors.dark) 0xff242424.toInt() else 0xffededf0.toInt()

    private fun styleChip(chip: TextView, byType: Boolean) {
        chip.isSelected = chip.text.toString() == if (byType) type else source
        val base = colors.background(if (chip.isSelected) colors.selected else chipColor(), dp(28).toFloat())
        chip.background = RippleDrawable(ColorStateList.valueOf(0x307a8497), base, null)
    }

    private fun resultsPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        val header = LinearLayout(this@SearchActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
        }
        summary = label("", 14f, colors.text)
        header.addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("修改筛选", 14f, colors.text).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { showFilters() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        addView(header)
        status = label("", 14f, colors.secondary).apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        addView(status)
        actionHost = FrameLayout(this@SearchActivity)
        val target = BlurTarget(this@SearchActivity).apply {
            setBackgroundColor(if (colors.dark) Color.BLACK else colors.page)
        }
        recycler = RecyclerView(this@SearchActivity).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SearchActivity.adapter
            itemAnimator = null
            clipToPadding = false
        }
        target.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        actionHost.addView(target, FrameLayout.LayoutParams(-1, -1))
        dragSelection = DragSelectionController(recycler, object : DragSelectionController.Host {
            override fun selectionKey(position: Int) = adapter.currentList.getOrNull(position)?.file?.path
            override fun isSelected(key: String) = key in selected
            override fun onSelectionChanges(changes: Map<String, Boolean>) {
                for ((path, chosen) in changes) {
                    if (chosen) selected.add(path) else selected.remove(path)
                }
                renderSelection()
            }
        })
        actionDock = createActionDock(target)
        actionHost.addView(actionDock, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.LEFT))
        actionHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutActionDock() }
        addView(actionHost, LinearLayout.LayoutParams(-1, 0, 1f))
        taskBanner = LinearLayout(this@SearchActivity).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            background = colors.background(colors.selected, dp(18).toFloat())
            setPadding(dp(16), 0, dp(4), 0)
            taskText = label("", 13f, colors.text)
            addView(taskText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton("close", "取消当前任务") { cancelTask() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        addView(taskBanner, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(16), 0, dp(16), dp(6)) })
    }

    private fun createActionDock(target: BlurTarget) = LiquidGlassView(this).apply {
        visibility = View.GONE
        setupWith(target).setFrameClearDrawable(android.graphics.drawable.ColorDrawable(
            if (colors.dark) Color.BLACK else colors.page))
        bindGlassTarget(target)
        actionButtons = LinearLayout(this@SearchActivity).apply { gravity = Gravity.CENTER }
        addView(actionButtons, FrameLayout.LayoutParams(-1, -1))
        addAction("compress", "压缩") { createArchive(selected.toList()) }
        addAction("copy", "复制") { transfer("copy") }
        addAction("move", "移动") { transfer("move") }
        addAction("rename", "重命名") { renameFiles(selected.map(::File), it) }
        addAction("transfer", "传输") { showLanSend(selected.map(::File)) }
        addAction("more", "更多") { showSelectionMenu() }
        glass.applySurface(this, style = LiquidGlassStyle.HAZE_DOCK)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            glass.applySurface(this, style = LiquidGlassStyle.HAZE_DOCK)
        }
    }

    private fun addAction(icon: String, title: String, run: (View) -> Unit) {
        val button = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            accessible(if (icon == "rename") "批量重命名" else title)
            setOnClickListener { anchor -> anchor.menuAnchor(); run(anchor) }
        }
        val graphic: View = if (icon == "rename") ClockRenameIconView(this).also { renameIcon = it }
            else ImageView(this).apply { setImageDrawable(Art.drawable(context, icon)) }
        graphic.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        button.addView(graphic, LinearLayout.LayoutParams(dp(24), dp(24)))
        button.addView(label(title, 13f, colors.text).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(-1, dp(20)))
        actionButtons.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun layoutActionDock() {
        if (!::actionDock.isInitialized || actionHost.width <= 0) return
        val gutter = ((actionHost.width - dp(1040)) / 2).coerceAtLeast(0)
        if (actionDock.visibility != View.VISIBLE) {
            dragSelection.bottomInsetPx = 0
            if (recycler.paddingLeft != gutter || recycler.paddingBottom != 0) {
                recycler.setPadding(gutter, 0, gutter, 0)
            }
            return
        }
        val metrics = DockGeometry.calculate(actionHost.width, resources.displayMetrics.density,
            resources.configuration.fontScale, DockGeometry.Mode.ACTIONS, actionButtons.childCount, root.paddingBottom)
        val lp = actionDock.layoutParams as FrameLayout.LayoutParams
        if (lp.width != metrics.capsule.width || lp.height != metrics.capsule.height ||
            lp.leftMargin != metrics.capsule.left || lp.bottomMargin != metrics.bottomClearance) {
            lp.width = metrics.capsule.width
            lp.height = metrics.capsule.height
            lp.leftMargin = metrics.capsule.left
            lp.bottomMargin = metrics.bottomClearance
            actionDock.layoutParams = lp
        }
        for (index in 0 until actionButtons.childCount) {
            val button = actionButtons.getChildAt(index) as LinearLayout
            val icon = button.getChildAt(0)
            val label = button.getChildAt(1)
            val iconParams = icon.layoutParams
            val labelParams = label.layoutParams as LinearLayout.LayoutParams
            if (iconParams.width != metrics.iconSize || iconParams.height != metrics.iconSize) {
                iconParams.width = metrics.iconSize
                iconParams.height = metrics.iconSize
                icon.layoutParams = iconParams
            }
            if (labelParams.height != metrics.labelHeight || labelParams.topMargin != metrics.iconLabelGap) {
                labelParams.height = metrics.labelHeight
                labelParams.topMargin = metrics.iconLabelGap
                label.layoutParams = labelParams
            }
        }
        dragSelection.bottomInsetPx = metrics.hostHeight
        if (recycler.paddingLeft != gutter || recycler.paddingBottom != metrics.hostHeight) {
            recycler.setPadding(gutter, 0, gutter, metrics.hostHeight)
        }
    }

    private fun toggleSelection(path: String) {
        if (!selected.add(path)) selected.remove(path)
        renderSelection()
    }

    private fun clearSelection() {
        dragSelection.cancelGesture()
        if (selected.isEmpty()) return
        selected.clear()
        renderSelection()
    }

    private fun renderSelection() {
        val active = selected.isNotEmpty()
        selectionTitle.text = "已选 ${selected.size} 项"
        if ((selectionToolbar.visibility == View.VISIBLE) != active) {
            searchToolbar.visibility = if (active) View.GONE else View.VISIBLE
            selectionToolbar.visibility = if (active) View.VISIBLE else View.GONE
            actionDock.visibility = if (active) View.VISIBLE else View.GONE
            renameIcon.setHostResumed(active && resumed)
            layoutActionDock()
            updateSelectionBack(active)
        }
        adapter.notifyItemRangeChanged(0, adapter.itemCount, SELECTION_PAYLOAD)
    }

    private fun updateSelectionBack(active: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        if (active) {
            val callback = OnBackInvokedCallback { clearSelection() }
            selectionBack = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        } else {
            selectionBack?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            selectionBack = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selected.isEmpty()) super.onBackPressed() else clearSelection()
    }

    private fun transfer(action: String) {
        val paths = selected.toList()
        if (paths.isNotEmpty()) fileOperation(action, directory ?: CreationOptions.sourceDirectory(paths), paths)
    }

    private fun showSelectionMenu() {
        val paths = selected.toList()
        if (paths.isEmpty()) return
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "分享" to { shareFiles(paths.map(::File)) },
            "删除" to { confirmDelete(paths, directory ?: CreationOptions.sourceDirectory(paths)) }
        )
        addPhotoTimeSyncAction(actions, paths.map(::File))
        if (paths.size == 1) actions.add("文件信息" to {
            val file = File(paths.single())
            showInfoPanel(file) { openResult(file, reveal = true) }
        })
        actions.add("取消选择" to ::clearSelection)
        menu("已选 ${paths.size} 项", actions.map { it.first }) { actions[it].second() }
    }

    override fun onFileOperationStarted() {
        startedOperation = true
        updateTaskBanner()
    }

    private fun observeFileEvents() {
        scope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ArchiveComplete -> {
                        if (startedOperation) clearSelection()
                        startedOperation = false
                        if (resumed) event.message?.let(::notice)
                        refreshResults()
                    }
                    is AppEvent.ExtractionComplete -> refreshResults()
                    is AppEvent.RenameComplete -> {
                        if (event.clearSelection) clearSelection()
                        refreshResults()
                    }
                    is AppEvent.PhotoTimeSyncComplete -> {
                        startedOperation = false
                        refreshResults()
                        if (resumed) messagePanel(PhotoTimeSync.TITLE, event.message, "好", false) {}
                    }
                    AppEvent.LanFilesReceived -> refreshResults()
                    is AppEvent.ArchiveError -> {
                        startedOperation = false
                        if (resumed) errorDialog("操作未完成", event.errorMessage ?: "请检查文件与输出位置")
                        refreshResults()
                    }
                    is AppEvent.ExtractionError -> if (resumed) errorDialog("解压未完成", event.errorMessage)
                    else -> Unit
                }
                updateTaskBanner()
            }
        }
    }

    private fun refreshResults() {
        catalog.invalidate()
        submitted?.let { submit(it, refresh = true) }
    }

    private fun updateTaskBanner() {
        taskText.text = ActiveOperation.phase
        taskBanner.visibility = if (ActiveOperation.busy()) View.VISIBLE else View.GONE
    }

    private fun showFilters() {
        clearSelection()
        pendingSearch?.cancel()
        submitted = null
        results.visibility = View.GONE
        filters.visibility = View.VISIBLE
        chips.forEach { (chip, byType) -> styleChip(chip, byType) }
    }

    private fun submit(raw: String, refresh: Boolean = false) {
        val query = raw.trim()
        val wanted = type.orEmpty()
        val selectedSource = source
        if (query.isEmpty() && wanted.isEmpty() && selectedSource == null) {
            showFilters()
            return
        }
        if (!refresh) clearSelection()
        pendingSearch?.cancel()
        submitted = query
        showKeyboardOnFocus = false
        WindowCompat.getInsetsController(window, field).hide(WindowInsetsCompat.Type.ime())
        root.requestFocus()
        filters.visibility = View.GONE
        results.visibility = View.VISIBLE
        summary.text = (listOfNotNull(type, selectedSource).ifEmpty {
            listOf(if (directory == null) "搜索结果" else "当前目录")
        }).joinToString(" · ")
        if (!refresh) adapter.submitList(emptyList())
        if (adapter.itemCount == 0) showStatus("正在查找…")
        pendingSearch = scope.launch {
            try {
                if (directory != null || selectedSource != null || wanted == "文件夹") {
                    val matches = withContext(Dispatchers.IO) { discover(query, wanted, selectedSource) }
                    publish(matches, true)
                } else {
                    catalog.snapshots(wanted, query, roots = roots, includeHidden = includeHidden).collect { snapshot ->
                        val matches = withContext(Dispatchers.Default) {
                            snapshot.entries.map { Match(it.file, false) }
                        }
                        publish(matches, snapshot.complete)
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                showStatus("无法读取搜索结果")
            }
        }
    }

    private suspend fun discover(query: String, wanted: String, selectedSource: String?): List<Match> {
        val task = currentCoroutineContext()
        val matches = linkedMapOf<String, Match>()
        fun add(file: File, folder: Boolean) {
            if (!file.name.contains(query, ignoreCase = true)) return
            if (folder && wanted.isNotEmpty() && wanted != "文件夹") return
            if (!folder && (wanted == "文件夹" || !CategoryCatalog.matches(wanted, file.name))) return
            matches[file.path] = Match(file, folder)
        }
        val local = directory
        if (selectedSource == null && local != null) {
            for (file in local.listFiles() ?: throw java.io.IOException("Directory unavailable")) {
                task.ensureActive()
                if (includeHidden || !file.name.startsWith('.')) add(file, file.isDirectory)
            }
        } else {
            val locations = if (selectedSource == null) roots else roots.flatMap { root ->
                SOURCES.getValue(selectedSource).map { File(root, it) }
            }.filter { it.isDirectory }
            val directories: (suspend (File) -> Unit)? = if (wanted == "文件夹") ({ file -> add(file, true) }) else null
            CatalogFileWalker(locations, includeHidden).forEachFile({ task.ensureActive() }, directories) {
                add(it, false)
            }
        }
        return matches.values.sortedWith(compareByDescending<Match> { it.folder }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }.thenBy { it.file.path })
    }

    private fun publish(matches: List<Match>, complete: Boolean) {
        dragSelection.cancelGesture()
        if (complete && selected.isNotEmpty()) {
            val valid = matches.mapTo(hashSetOf()) { it.file.path }
            if (selected.retainAll(valid)) renderSelection()
        }
        adapter.submitList(matches)
        showStatus(when {
            !complete -> "正在查找…"
            matches.isEmpty() -> "没有找到文件"
            else -> ""
        })
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        if (::field.isInitialized) WindowCompat.getInsetsController(window, field).hide(WindowInsetsCompat.Type.ime())
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(0, R.anim.search_page_out)
    }

    override fun onDestroy() {
        pendingSearch?.cancel()
        updateSelectionBack(false)
        dragSelection.dispose()
        catalog.close()
        super.onDestroy()
    }

    private fun openResult(file: File, reveal: Boolean = false) {
        if (!reveal && !file.isDirectory && previewKind(fileMimeType(file)) != null) {
            val files = adapter.currentList.mapNotNull { if (it.folder) null else it.file }
            scope.launch {
                try {
                    startActivity(MediaPreviewIntent.create(this@SearchActivity, file, files))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    notice("无法预览此文件")
                }
            }
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, file.path).putExtra(EXTRA_REVEAL, reveal))
        finish()
    }

    private data class Match(val file: File, val folder: Boolean)

    private inner class SearchRows : ListAdapter<Match, SearchRows.Holder>(object : DiffUtil.ItemCallback<Match>() {
        override fun areItemsTheSame(old: Match, new: Match) = old.file.path == new.file.path
        override fun areContentsTheSame(old: Match, new: Match) = old == new
    }) {
        inner class Holder(row: LinearLayout, val icon: ImageView, val name: TextView, val path: TextView, val check: ImageView)
            : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@SearchActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(18), dp(10))
                minimumHeight = dp(64)
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
            val icon = ImageView(this@SearchActivity)
            val name = label("", 16f, colors.text).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val path = label("", 13f, colors.secondary).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
            val words = LinearLayout(this@SearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(path, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
            row.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            val check = ImageView(this@SearchActivity).apply {
                setImageDrawable(Art.drawable(context, "check"))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(check, LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(10) })
            return Holder(row, icon, name, path, check)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            holder.name.text = item.file.name
            holder.path.text = item.file.parent.orEmpty()
            holder.icon.setImageDrawable(Art.drawable(this@SearchActivity, fileArt(item.file, item.folder)))
            bindSelection(holder, item.file.path)
            holder.itemView.setOnClickListener {
                if (selected.isEmpty()) openResult(item.file) else toggleSelection(item.file.path)
            }
            holder.itemView.setOnLongClickListener {
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                if (!dragSelection.beginSelection(position)) toggleSelection(getItem(position).file.path)
                true
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (SELECTION_PAYLOAD in payloads) bindSelection(holder, getItem(position).file.path)
            else super.onBindViewHolder(holder, position, payloads)
        }

        private fun bindSelection(holder: Holder, path: String) {
            val chosen = path in selected
            holder.itemView.isSelected = chosen
            holder.itemView.setBackgroundColor(if (chosen) colors.selected else Color.TRANSPARENT)
            holder.check.visibility = when {
                chosen -> View.VISIBLE
                selected.isNotEmpty() -> View.INVISIBLE
                else -> View.GONE
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("type", type)
        out.putString("source", source)
        out.putString("input", field.text.toString())
        out.putString("submitted", submitted)
        out.putStringArrayList("selected", ArrayList(selected))
        out.putBoolean("startedOperation", startedOperation)
        super.onSaveInstanceState(out)
    }

    companion object {
        const val EXTRA_ROOTS = "search_roots"
        const val EXTRA_DIRECTORY = "search_directory"
        const val EXTRA_HIDDEN = "search_hidden"
        const val EXTRA_QUERY = "search_query"
        const val EXTRA_RESULT = "search_result"
        const val EXTRA_REVEAL = "search_reveal"
        private const val SELECTION_PAYLOAD = "search_selection"
        private val TYPES = listOf("文档", "图片", "视频", "音乐", "压缩包", "安装包", "文件夹")
        private val SOURCES = linkedMapOf(
            "相机" to listOf("DCIM/Camera", "Pictures/Camera"),
            "微信" to listOf("Android/media/com.tencent.mm", "Tencent/MicroMsg", "Pictures/WeiXin", "Movies/WeChat", "Download/WeiXin"),
            "QQ" to listOf("Android/media/com.tencent.mobileqq", "Tencent/QQfile_recv", "Tencent/QQ_Images", "Pictures/QQ", "Download/QQ"),
            "截屏录屏" to listOf("DCIM/Screenshots", "Pictures/Screenshots", "DCIM/ScreenRecorder", "Movies/ScreenRecorder", "Movies/Screen recordings"),
            "录音机" to listOf("Recordings", "Sounds", "Music/Recordings", "MIUI/sound_recorder")
        )
    }
}
