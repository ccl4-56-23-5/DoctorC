/* Native ordered-rule batch renamer, 2026-11-04. GPL-3.0-or-later. */
package com.wirelessalien.zipxtract.nativeui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.method.ReplacementTransformationMethod
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.graphics.text.LineBreaker
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.nativeui.RenamerRules.Kind
import com.wirelessalien.zipxtract.nativeui.RenamerRules.Rule
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * ReNamer-style ordered rules, adapted to a phone: independently scrolling rule and file panes.
 * There is deliberately no always-expanded rule form ahead of the preview. Parameters live in a
 * separate, IME-safe editor. This is not a Windows EXIF/Pascal/ExifTool compatibility layer.
 *
 * Preview and transactional execution run off the UI thread. onFinished is delivered on the main
 * thread exactly once after an execution result, even AFTER the originating Activity is destroyed.
 * The consumer must only repair application-scoped state/notify current screens, not old Views.
 * UI destruction cancels previews, never an in-flight rename or its completion delivery.
 */
object BatchRenameDialog {
    fun show(activity: Activity, files: List<File>, origin: View? = null,
             onFinished: (BatchRenameEngine.Result) -> Unit = {}): MotionDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(activity, "安全批量重命名需要 Android 8.0 及以上版本", Toast.LENGTH_LONG).show()
            return null
        }
        if (files.isEmpty()) {
            Toast.makeText(activity, "请先选择需要批量重命名的文件或文件夹", Toast.LENGTH_SHORT).show()
            return null
        }
        return Panel(activity, files.toList(), origin, onFinished).open()
    }

    // A drawable space stays visible at a line boundary and copies as the original character.
    private class PreviewSpaceSpan(private val marker: Paint) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
                             fm: Paint.FontMetricsInt?): Int = ceil(paint.measureText(" ")).toInt().coerceAtLeast(1)

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
                          top: Int, y: Int, bottom: Int, paint: Paint) {
            val width = getSize(paint, text, start, end, null)
            canvas.drawSpaceGuide(x, x + width, y.toFloat(), paint.textSize, marker)
        }
    }

    // Display characters are one-to-one with the original UTF-16 offsets. Android can then
    // draw a marker at a line boundary instead of treating it as removable trailing whitespace.
    private object VisiblePreviewSpaces : ReplacementTransformationMethod() {
        override fun getOriginal() = charArrayOf(' ')
        override fun getReplacement() = charArrayOf('\u00A0')
    }

    @android.annotation.SuppressLint("AppCompatCustomView") // NativeTheme uses platform TextView styling.
    private class PreviewNameView(
        context: Context, prefix: String, filename: String, size: Float, color: Int, bold: Boolean, markerColor: Int
    ) : TextView(context) {
        init {
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextIsSelectable(true)
            if (Build.VERSION.SDK_INT >= 29) breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            val value = prefix + filename
            if (' ' in filename) {
                transformationMethod = VisiblePreviewSpaces
                val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = markerColor }
                text = SpannableString(value).apply {
                    filename.forEachIndexed { index, char ->
                        if (char == ' ') {
                            val offset = prefix.length + index
                            setSpan(PreviewSpaceSpan(marker), offset, offset + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            } else {
                text = value
            }
        }

        override fun onTextContextMenuItem(id: Int): Boolean {
            // TextView normally copies its transformed text. Keep the literal filename characters.
            if (id == android.R.id.copy && hasSelection()) {
                val start = minOf(selectionStart, selectionEnd)
                val end = maxOf(selectionStart, selectionEnd)
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(null, text.subSequence(start, end).toString()))
                clearFocus()
                return true
            }
            return super.onTextContextMenuItem(id)
        }
    }

    private class Panel(private val activity: Activity, private val files: List<File>, private val origin: View?,
                        private val onFinished: (BatchRenameEngine.Result) -> Unit) {
        private val c = activity
        private val p = Palette(c)
        private val danger = if (p.dark) 0xffff9a91.toInt() else 0xffbb3930.toInt()
        private val main = Handler(Looper.getMainLooper())
        // Separate target on the same Looper: cleanUp must NOT remove final result delivery.
        private val completionMain = Handler(Looper.getMainLooper())
        private val io = Executors.newSingleThreadExecutor { task ->
            Thread(task, "batch-rename-io").apply { isDaemon = true }
        }
        private val dialog = MotionDialog(c, R.style.NativeTheme).apply { morphFrom = origin }
        private val menus = NativePopups(c)
        private val presetStore = RenamePresetStore(c)
        private var editor: MotionDialog? = null
        private var presetName = ""
        private var presetId: String? = null
        private var restoring = true
        private var fileOrder = RenameFileOrder.Settings()
        private var presetRevision = 0
        private val rules = mutableListOf<Rule>()
        private var selected = -1
        private var closed = false
        private var busy = false
        private var terminal = false
        private var resultDelivered = false // Main-thread confined, set before invoking consumer code.
        private var registered = false
        private var generation = 0
        private var previewFuture: Future<*>? = null
        private var debounce: Runnable? = null
        private var latestPlan: BatchRenameEngine.Plan? = null
        private var pageIndex = 0
        private val pageSize = 20
        private val surface = column().apply {
            tag = "batch_rename_panel"
            setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
            background = p.background(p.page, c.dp(24).toFloat())
        }
        private val ruleRows = column().apply { tag = "batch_rename_rules" }
        private val ruleScroll = ScrollView(c).apply {
            tag = "batch_rename_rules_scroll"; isFillViewport = true
            addView(ruleRows, ViewGroup.LayoutParams(-1, -2))
        }
        private val ruleHeading = c.label("规则 · 0 条", 16f, p.text, true).apply { tag = "batch_rename_rule_count" }
        private val previewHeader = c.label("原名 → 新名 · ${files.size} 项", 15f, p.text, true)
        private val previewRows = column().apply { tag = "batch_rename_preview" }
        private val batchErrors = c.label("", 13f, danger).apply {
            tag = "batch_rename_batch_errors"; visibility = View.GONE
        }
        private val resultText = c.label("", 14f, p.text).apply {
            tag = "batch_rename_result"; visibility = View.GONE; setTextIsSelectable(true)
        }
        private val previewBody = column().apply {
            addView(batchErrors, LinearLayout.LayoutParams(-1, -2))
            addView(resultText, LinearLayout.LayoutParams(-1, -2))
            addView(previewRows, LinearLayout.LayoutParams(-1, -2))
        }
        private val previewScroll = ScrollView(c).apply {
            tag = "batch_rename_preview_scroll"; isFillViewport = true
            addView(previewBody, ViewGroup.LayoutParams(-1, -2))
        }
        private val status = c.label("正在生成预览…", 13f, p.secondary).apply {
            tag = "batch_rename_status"; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, c.dp(5), 0, c.dp(5))
        }
        private val progress = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "batch_rename_progress"; isIndeterminate = true; visibility = View.GONE
        }
        private val add = action("＋ 添加", "add") { openEditor(-1) }
        private val edit = action("编辑", "edit") { if (selected in rules.indices) openEditor(selected) }
        private val renameRule = action("改名", "rename_rule") { if (selected in rules.indices) openEditor(selected, true) }
        private val remove = action("移除", "remove") {
            if (mutable() && selected in rules.indices) {
                rules.removeAt(selected); selected = minOf(selected, rules.lastIndex); rulesChanged()
            }
        }
        private val up = action("上移", "up") { move(-1) }
        private val down = action("下移", "down") { move(1) }
        private val savePreset = action("保存", "save_preset") { savePreset() }.apply { contentDescription = "保存规则预设" }
        private val loadPreset = action("载入 ▾", "load_preset") { loadPreset() }.apply { contentDescription = "载入规则方案菜单" }
        private val sortFiles = action("排序 ▾", "sort_files") { sortMenu() }.apply { contentDescription = "选中文件排序菜单" }
        private val direction = action("升序 ↑", "sort_direction") {
            if (mutable()) { fileOrder = fileOrder.copy(descending = !fileOrder.descending); updateOrder(); schedulePreview() }
        }
        private val orderLabel = c.label(fileOrder.title, 12f, p.secondary).apply { tag = "batch_rename_order" }
        private val presetLabel = c.label("未载入方案 · 可保存多套规则", 12f, p.secondary).apply { tag = "batch_rename_preset_name" }
        private val close = action("取消", "close") { if (!busy) dialog.close { cleanUp() } }
        private val execute = action("执行重命名", "execute", true) { executePlan() }
        private val previous = action("上一页", "previous") { changePage(-1) }
        private val next = action("下一页", "next") { changePage(1) }
        private val pageLabel = c.label("", 12f, p.secondary).apply { gravity = Gravity.CENTER }
        private val pager = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(previous, LinearLayout.LayoutParams(0, c.dp(42), 1f))
            addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(next, LinearLayout.LayoutParams(0, c.dp(42), 1f))
            visibility = View.GONE
        }
        private val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(a: Activity) {
                if (a === activity) { cleanUp(); dialog.dispose() }
            }
            override fun onActivityCreated(a: Activity, state: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
        }

        fun open(): MotionDialog {
            ViewCompat.setAccessibilityPaneTitle(surface, "有序重命名规则与文件预览")
            surface.addView(c.label("批量重命名", 21f, p.text, true), lp(-1, -2, bottom = 7))
            surface.addView(ruleHeading, lp(-1, -2, bottom = 5))
            surface.addView(actionRow(add, edit, renameRule, remove), lp(-1, -2, bottom = 4))
            surface.addView(actionRow(up, down, savePreset, loadPreset), lp(-1, -2, bottom = 5))
            surface.addView(presetLabel, lp(-1, -2, bottom = 5))
            // Both panes get an independent viewport; a long rule queue cannot push preview away.
            surface.addView(ruleScroll, LinearLayout.LayoutParams(-1, 0, .42f))
            surface.addView(previewHeader, lp(-1, -2, top = 8, bottom = 4))
            val fileTools = LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(orderLabel, LinearLayout.LayoutParams(0, -2, 1f))
                addView(sortFiles, LinearLayout.LayoutParams(c.dp(72), -2).apply { leftMargin = c.dp(5) })
                addView(direction, LinearLayout.LayoutParams(c.dp(72), -2).apply { leftMargin = c.dp(5) })
            }
            surface.addView(fileTools, lp(-1, -2, bottom = 5))
            surface.addView(previewScroll, LinearLayout.LayoutParams(-1, 0, .58f))
            surface.addView(pager, lp(-1, -2))
            surface.addView(status, lp(-1, -2))
            surface.addView(progress, lp(-1, c.dp(3), bottom = 5))
            surface.addView(actionRow(close, execute), lp(-1, -2, top = 5))
            dialog.setOnDismissListener { cleanUp() }
            dialog.onBackAction = { busy || consumeIme(dialog) }
            activity.application.registerActivityLifecycleCallbacks(lifecycle); registered = true
            val height = minOf(c.dp(860), (c.resources.displayMetrics.heightPixels * .92f).toInt())
            surface.minimumHeight = height
            dialog.showPanel(surface, c.dp(680))
            responsive(dialog, surface, height)
            renderRules(); status.text = "正在恢复上次规则…"
            io.execute {
                val loaded = runCatching { presetStore.lastUsed() }
                main.post {
                    if (!closed) {
                        loaded.onSuccess { last -> if (last != null) {
                            rules.addAll(last.rules); fileOrder = last.order; presetId = last.presetId; presetName = last.name
                            selected = if (rules.isEmpty()) -1 else 0
                            presetLabel.text = if (presetName.isNotEmpty()) "方案 · $presetName" else "上次使用的规则 · 可另存为方案"
                        } }.onFailure { Toast.makeText(c, "上次规则读取失败，已保留原存储：${it.message}", Toast.LENGTH_LONG).show() }
                        restoring = false; updateOrder(); renderRules(); schedulePreview(false)
                    }
                }
            }
            return dialog
        }

        private fun column() = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(w, h).apply {
            topMargin = c.dp(top); bottomMargin = c.dp(bottom)
        }
        private fun action(text: String, key: String, primary: Boolean = false, click: () -> Unit): TextView =
            c.label(text, 14f, if (primary) p.onAccent else p.text, primary).apply {
                tag = "batch_rename_$key"; gravity = Gravity.CENTER; minHeight = c.dp(48)
                setPadding(c.dp(5), c.dp(9), c.dp(5), c.dp(9))
                background = p.background(if (primary) p.blue else p.surface, c.dp(13).toFloat())
                isClickable = true; isFocusable = true
                setOnClickListener { if (!closed) { menuAnchor(); click() } }
            }
        private fun actionRow(vararg views: View) = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            views.forEachIndexed { i, view ->
                addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = c.dp(5) })
            }
        }
        private fun enabled(view: View, value: Boolean) { view.isEnabled = value; view.alpha = if (value) 1f else .38f }
        private fun mutable() = !closed && !busy && !terminal && !restoring
        private fun note(parent: LinearLayout, text: String) {
            parent.addView(c.label(text, 13f, p.secondary).apply { setLineSpacing(c.dp(2).toFloat(), 1f) },
                lp(-1, -2, top = 6, bottom = 6))
        }
        private fun renderRules() {
            ruleRows.removeAllViews()
            ruleHeading.text = "规则 · ${rules.count { it.enabled }}/${rules.size} 条启用 · 依次执行"
            if (rules.isEmpty()) note(ruleRows, "尚无规则。点击“＋ 添加”，先选择规则类型。下次打开会恢复最后使用的规则和排序。")
            rules.forEachIndexed { index, rule ->
                val row = LinearLayout(c).apply {
                    tag = "batch_rename_rule_$index"; gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = c.dp(65); setPadding(c.dp(10), c.dp(6), c.dp(5), c.dp(6))
                    background = p.pressable(if (index == selected) p.selected else p.surface, c.dp(15).toFloat())
                    isClickable = true; isFocusable = true
                    contentDescription = "编辑第 ${index + 1} 条${rule.kind.title}规则"
                    setOnClickListener {
                        if (mutable()) {
                            selected = index
                            menuAnchor()
                            openEditor(index, focusOutput = true)
                        }
                    }
                    setOnLongClickListener {
                        if (mutable()) { selected = index; renderRules() }
                        true
                    }
                }
                val text = column().apply {
                    addView(c.label("${index + 1} · ${rule.name.ifBlank { rule.kind.title }}${if (!rule.enabled) "（已禁用）" else ""}", 14f, p.text, true))
                    addView(c.label(RenamerRules.describe(rule), 12f, p.secondary).apply {
                        tag = "batch_rename_rule_description_$index"; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    }, lp(-1, -2, top = 4))
                    alpha = if (rule.enabled) 1f else .55f
                }
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(NativeToggle(c).apply {
                    tag = "batch_rename_rule_enabled_$index"; contentDescription = "启用第 ${index + 1} 条${rule.kind.title}规则"
                    isChecked = rule.enabled; isEnabled = mutable()
                    setOnCheckedChangeListener { _, value ->
                        if (mutable() && index in rules.indices && rules[index].enabled != value) {
                            rules[index] = rules[index].copy(enabled = value); selected = index; rulesChanged()
                        }
                    }
                }, LinearLayout.LayoutParams(c.dp(58), c.dp(48)))
                enabled(row, mutable())
                ruleRows.addView(row, lp(-1, -2, bottom = 5))
            }
            enabled(add, mutable() && rules.size < 64)
            enabled(edit, mutable() && selected in rules.indices); enabled(renameRule, mutable() && selected in rules.indices)
            enabled(remove, mutable() && selected in rules.indices)
            enabled(up, mutable() && selected > 0)
            enabled(down, mutable() && selected in 0 until rules.lastIndex)
            enabled(savePreset, mutable() && rules.isNotEmpty()); enabled(loadPreset, mutable())
            enabled(sortFiles, mutable()); enabled(direction, mutable())
        }
        private fun rulesChanged() {
            presetRevision++
            renderRules(); schedulePreview()
            ruleRows.post { if (!closed) ruleRows.findViewWithTag<View>("batch_rename_rule_$selected")?.let {
                it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true)
            } }
        }
        private fun move(delta: Int) {
            if (!mutable() || selected !in rules.indices || selected + delta !in rules.indices) return
            val rule = rules.removeAt(selected); selected += delta; rules.add(selected, rule); rulesChanged()
            ruleRows.post { if (!closed) ruleRows.findViewWithTag<View>("batch_rename_rule_$selected")?.let {
                it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true)
            } }
        }

        private data class Mode(val value: String, val title: String)
        private fun modes(kind: Kind): List<Mode> {
            fun choices(vararg pairs: Pair<String, String>) = pairs.map { Mode(it.first, it.second) }
            val insert = choices("suffix" to "作为后缀", "prefix" to "作为前缀", "position" to "指定位置插入")
            return when (kind) {
                Kind.INSERT, Kind.USER_INPUT -> insert
                Kind.DATE, Kind.SERIALIZE, Kind.RANDOM -> insert + Mode("replace", "替换当前名称")
                Kind.DELETE -> choices("all" to "删除当前名称", "range" to "删除指定范围", "first" to "删除开头字符", "last" to "删除末尾字符")
                Kind.REMOVE -> choices("all" to "移除所有匹配文本", "first" to "仅移除首次匹配", "last" to "仅移除末次匹配")
                Kind.REPLACE -> choices("all" to "替换所有匹配", "first" to "仅替换首次匹配", "last" to "仅替换末次匹配")
                Kind.REGEX -> choices("all" to "替换所有正则匹配", "first" to "仅替换首次正则匹配")
                Kind.EXTENSION -> choices("replace" to "更改扩展名", "add" to "追加扩展名", "remove" to "移除扩展名", "lower" to "扩展名小写", "upper" to "扩展名大写")
                Kind.CASE -> choices("lower" to "全部小写", "upper" to "全部大写", "title" to "每词首字母大写", "sentence" to "句首大写", "invert" to "大小写反转")
                Kind.CLEANUP -> choices("trim" to "清理首尾空白", "spaces" to "合并空格", "whitespace" to "合并各类空白", "control" to "去控制字符", "all" to "综合清理")
                Kind.STRIP -> choices("digits" to "去数字", "letters" to "去字母", "symbols" to "去符号", "brackets" to "去括号内容", "diacritics" to "去拉丁重音", "all" to "仅保留字母数字")
                Kind.REARRANGE -> choices("reverse" to "反转字符", "words" to "反转词序", "parts" to "按分段序号重排")
                Kind.PAD -> choices("left" to "左侧补齐", "right" to "右侧补齐", "numbers" to "数字段补齐")
                Kind.MAPPING -> choices("exact" to "单项名称映射", "lines" to "多行旧名=>新名")
                Kind.TRANSLITERATE -> choices("latin" to "基础拉丁去重音（非汉语拼音）", "custom" to "自定义字符一对一映射")
            }
        }
        private inner class Choice(key: String, private val values: List<String>): androidx.appcompat.widget.AppCompatTextView(c) {
            private var selectedIndex = 0
            var onChoice: ((Int) -> Unit)? = null
            init {
                tag = "batch_rename_rule_$key"; minimumHeight = c.dp(48); textSize = 15f; maxLines = 2
                setTextColor(p.text); setPadding(c.dp(11), c.dp(10), c.dp(11), c.dp(10))
                background = p.background(p.surface, c.dp(11).toFloat()); gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true; setSelection(0)
                setOnClickListener {
                    menuAnchor(); menus.show(contentDescription?.toString() ?: "选择", values, selectedIndex) { index ->
                        if (mutable() && isAttachedToWindow) { setSelection(index); onChoice?.invoke(index) }
                    }
                }
            }
            fun setSelection(index: Int) { selectedIndex = index.coerceIn(0, values.lastIndex); text = "${values[selectedIndex]}  ▾" }
        }
        private fun spinner(key: String, values: List<String>) = Choice(key, values)
        private fun caption(parent: LinearLayout, title: String, view: View) {
            parent.addView(c.label(title, 13f, p.secondary).apply { labelFor = view.id }, lp(-1, -2, top = 10, bottom = 5))
            parent.addView(view, lp(-1, -2))
        }
        private fun toggleRow(parent: LinearLayout, text: String, key: String, checked: Boolean): NativeToggle {
            val toggle = NativeToggle(c).apply {
                tag = "batch_rename_rule_$key"; contentDescription = text; isChecked = checked
            }
            parent.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = c.dp(48)
                addView(c.label(text, 14f, p.text).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(toggle, LinearLayout.LayoutParams(c.dp(58), c.dp(48)))
                setOnClickListener { toggle.isChecked = !toggle.isChecked }
            }, lp(-1, -2, top = 6))
            return toggle
        }

        private fun openEditor(index: Int, focusName: Boolean = false, focusOutput: Boolean = false) {
            if (!mutable() || editor != null || (index < 0 && rules.size >= 64)) return
            var draft = rules.getOrNull(index) ?: Rule(Kind.INSERT, mode = "suffix")
            val modal = MotionDialog(c, R.style.NativeTheme).apply { morphFrom = if (index < 0) add else if (focusName) renameRule else edit }
            editor = modal
            val card = column().apply {
                tag = "batch_rename_rule_editor"; setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(12))
                background = p.background(p.page, c.dp(24).toFloat())
            }
            ViewCompat.setAccessibilityPaneTitle(card, if (index < 0) "添加重命名规则" else "编辑重命名规则")
            card.addView(c.label(if (focusName) "规则改名" else if (index < 0) "添加规则" else "编辑第 ${index + 1} 条规则", 20f, p.text, true), lp(-1, -2, bottom = 6))
            val body = column()
            val scroll = ScrollView(c).apply {
                tag = "batch_rename_rule_editor_scroll"; isFillViewport = true; addView(body, ViewGroup.LayoutParams(-1, -2))
            }
            val kinds = Kind.values().toList()
            val kindChoice = spinner("kind", kinds.map { it.title }).apply {
                contentDescription = "规则类型"; setSelection(kinds.indexOf(draft.kind))
            }
            caption(body, "规则类型", kindChoice)
            var modeList = modes(draft.kind)
            // Normalize the core's generic default alias to an explicit UI choice.
            draft = draft.copy(mode = modeList.firstOrNull { it.value == draft.mode }?.value ?: modeList.first().value)
            var modeChoice = spinner("mode", modeList.map { it.title }).apply {
                contentDescription = "规则方式"; setSelection(modeList.indexOfFirst { it.value == draft.mode })
            }
            val modeContainer = column()
            body.addView(modeContainer, lp(-1, -2))
            caption(modeContainer, "执行方式", modeChoice)
            val form = column()
            body.addView(form, lp(-1, -2))
            val fields = linkedMapOf<String, EditText>()
            val dateSources = RenamerRules.DateSource.values().toList()
            var skip: NativeToggle? = null
            var ignore: NativeToggle? = null
            val error = c.label("", 13f, danger).apply { tag = "batch_rename_rule_validation"; visibility = View.GONE; maxLines = 3 }
            fun input(key: String, title: String, value: String, numeric: Boolean = false) {
                val field = (if (!numeric && (key == "text" || key == "replacement")) LiteralRuleEditText(c) else EditText(c)).apply {
                    id = View.generateViewId(); tag = "batch_rename_rule_$key"; setTextColor(p.text); setHintTextColor(p.secondary)
                    textSize = 15f; minHeight = c.dp(48)
                    background = p.background(p.surface, c.dp(11).toFloat())
                    setPadding(c.dp(11), c.dp(10), c.dp(11), c.dp(10))
                    inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    maxLines = if (numeric) 1 else 5
                    setText(value)
                }
                fields[key] = field; caption(form, title, field)
                if (key == "text" || key == "replacement") {
                    val spaces = c.label("", 12f, p.secondary).apply { tag = "batch_rename_rule_spaces_$key" }
                    fun explain() {
                        val count = field.text.count { it == ' ' }
                        spaces.text = "空格 $count 个，首尾原样保留"
                        spaces.visibility = if (count > 0) View.VISIBLE else View.GONE
                        field.invalidate()
                    }
                    field.addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { explain() }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    }); explain(); form.addView(spaces, lp(-1, -2, top = 4))
                }
            }
            fun readValues(): Rule {
                fun text(key: String, fallback: String) = fields[key]?.text?.toString() ?: fallback
                fun long(key: String, fallback: Long): Long = fields[key]?.let {
                    it.text.toString().trim().toLongOrNull() ?: throw IllegalArgumentException("${it.contentDescription ?: key}必须为有效整数")
                } ?: fallback
                fun int(key: String, fallback: Int): Int = fields[key]?.let {
                    it.text.toString().trim().toIntOrNull() ?: throw IllegalArgumentException("$key 必须为有效整数")
                } ?: fallback
                return draft.copy(name = text("name", draft.name).trim().also { require(it.length <= 80) { "规则名称最多 80 字" } },
                    text = text("text", draft.text), replacement = text("replacement", draft.replacement),
                    position = int("position", draft.position), count = int("count", draft.count),
                    start = long("start", draft.start), step = long("step", draft.step), width = int("width", draft.width),
                    dateFormat = text("date_format", draft.dateFormat), skipExtension = skip?.isChecked ?: draft.skipExtension,
                    ignoreCase = ignore?.isChecked ?: draft.ignoreCase)
            }
            fun renderFields() {
                form.removeAllViews(); fields.clear(); skip = null; ignore = null; error.visibility = View.GONE
                val kind = draft.kind; val mode = draft.mode
                input("name", "规则名称（可选，留空显示类型）", draft.name)
                if (kind != Kind.EXTENSION) skip = toggleRow(form, "忽略扩展名（文件夹按完整名称）", "skip_extension", draft.skipExtension)
                when (kind) {
                    Kind.INSERT, Kind.USER_INPUT -> {
                        input("text", if (kind == Kind.INSERT) "插入文本（空格原样保留）" else "对所有选中文件应用这段文本", draft.text)
                        if (kind == Kind.INSERT) {
                            form.addView(action("插入修改时间占位符", "metadata") {
                                fields["text"]?.let { field ->
                                    val at = field.selectionStart.coerceAtLeast(0)
                                    field.text.insert(at, ":File DateModified:")
                                }
                            }, lp(-1, -2, top = 5))
                            input("date_format", "修改时间格式（占位符使用原文件 mtime）", draft.dateFormat)
                            note(form, "支持 :File DateModified:、:File Name:、:File BaseName:、:File Extension:、:File Size:。不支持 Windows EXIF / Pascal。")
                        }
                    }
                    Kind.DELETE -> if (mode != "all") {
                        if (mode == "range") input("position", "起始位置（从 1 开始）", draft.position.toString(), true)
                        input("count", if (mode == "range") "删除字符数（0 为删到末尾）" else "删除字符数（0 为 1）", draft.count.toString(), true)
                    }
                    Kind.REMOVE -> input("text", "要移除的字面文本", draft.text)
                    Kind.REPLACE, Kind.REGEX -> {
                        input("text", if (kind == Kind.REGEX) "Java 正则表达式" else "查找字面文本", draft.text)
                        input("replacement", "替换为（允许留空）", draft.replacement)
                        if (kind == Kind.REGEX) note(form, "Java 正则；分组引用可用 \$1 / \${name}。非法正则或分组会明确报错。")
                    }
                    Kind.EXTENSION -> {
                        if (mode == "replace" || mode == "add") input("text", "扩展名（可带一个前导点）", draft.text)
                        note(form, "仅处理文件最后一段扩展名；对文件夹无效。")
                    }
                    Kind.SERIALIZE -> {
                        input("start", "起始值（≥ 0）", draft.start.toString(), true)
                        input("step", "步长（> 0）", draft.step.toString(), true)
                        input("width", "最少位数（0—18，0 不补零）", draft.width.toString(), true)
                        input("text", "编号前文本", draft.text); input("replacement", "编号后文本", draft.replacement)
                        note(form, "按预览排序编号，默认不加空格或分隔符；需要时在编号前后文本中输入。")
                    }
                    Kind.REARRANGE -> if (mode == "parts") {
                        input("text", "分段分隔符（空为 _）", draft.text)
                        input("replacement", "新顺序（逗号分隔，如 2,1,3）", draft.replacement)
                    }
                    Kind.PAD -> {
                        input("width", "目标位数（0—1024）", draft.width.toString(), true)
                        input("text", "填充字符（单字符，空为 0）", draft.text)
                    }
                    Kind.DATE -> {
                        val sourceChoice = spinner("date_source", dateSources.map { it.title }).apply {
                            id = View.generateViewId()
                            contentDescription = "日期来源"
                            setSelection(dateSources.indexOf(draft.dateSource))
                            onChoice = { draft = draft.copy(dateSource = dateSources[it]) }
                        }
                        caption(form, "日期来源", sourceChoice)
                        note(form, "选择 EXIF 拍摄时间时，缺少该日期的文件会在预览中报错。")
                        input("date_format", "日期格式", draft.dateFormat)
                        input("text", "日期前文本", draft.text); input("replacement", "日期后文本", draft.replacement)
                        note(form, "格式示例 yyyy-MM-dd HH.mm.ss。EXIF 保留照片记录的拍摄日期和时间。")
                    }
                    Kind.MAPPING -> {
                        input("text", if (mode == "lines") "每行：旧名称=>新名称" else "旧名称", draft.text)
                        if (mode != "lines") input("replacement", "新名称", draft.replacement)
                    }
                    Kind.TRANSLITERATE -> if (mode == "custom") {
                        input("text", "原字符序列", draft.text); input("replacement", "对应字符（逐字符、等长）", draft.replacement)
                    } else note(form, "基础拉丁去重音；不声称汉语拼音或完整 Windows 音译。")
                    Kind.RANDOM -> {
                        input("text", "字符集（空为英文字母和数字）", draft.text)
                        input("count", "字符数（0 为 8，最多 128）", draft.count.toString(), true)
                        note(form, "使用确定性随机文本，同一计划的预览与执行一致。")
                    }
                    Kind.CASE, Kind.CLEANUP, Kind.STRIP -> Unit
                }
                if (mode == "position") input("position", "插入位置（从 1 开始）", draft.position.toString(), true)
                if (kind in listOf(Kind.REMOVE, Kind.REPLACE, Kind.REGEX, Kind.MAPPING))
                    ignore = toggleRow(form, "忽略大小写", "ignore_case", draft.ignoreCase)
                if (kind == Kind.DELETE && mode == "all")
                    note(form, "删除当前处理范围的名称。可在后续插入规则中加入修改时间等文本；只校验最终名称。")
            }
            fun bindModes() {
                modeChoice.onChoice = choose@ { pos ->
                        val choice = modeList.getOrNull(pos) ?: return@choose
                        if (choice.value != draft.mode) {
                            draft = runCatching { readValues() }.getOrDefault(draft).copy(mode = choice.value); renderFields()
                        }
                }
            }
            bindModes()
            kindChoice.onChoice = choose@ { pos ->
                    val kind = kinds.getOrNull(pos) ?: return@choose
                    if (kind == draft.kind) return@choose
                    // A new kind starts with no implicit number separator or inherited literal spaces.
                    draft = Rule(kind, text = "", replacement = "", enabled = draft.enabled, name = fields["name"]?.text?.toString()?.trim() ?: draft.name)
                    modeList = modes(kind); draft = draft.copy(mode = modeList.first().value)
                    modeContainer.removeAllViews()
                    modeChoice = spinner("mode", modeList.map { it.title }).apply { contentDescription = "规则方式" }
                    caption(modeContainer, "执行方式", modeChoice); bindModes(); renderFields()
            }
            renderFields()
            card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            card.addView(error, lp(-1, -2, top = 5))
            val cancel = action("取消", "rule_cancel") { hideKeyboard(modal); modal.close {} }
            val save = action("保存规则", "rule_save", true) {
                if (mutable()) {
                    runCatching {
                        val rule = readValues()
                        val errors = RenamerRules.validate(listOf(rule.copy(enabled = true)))
                        require(errors.isEmpty()) { errors.joinToString("\n") }
                        rule
                    }.fold({ rule ->
                        if (index in rules.indices) { rules[index] = rule; selected = index }
                        else { rules.add(rule); selected = rules.lastIndex }
                        hideKeyboard(modal)
                        modal.close { if (!closed) rulesChanged() }
                    }, { failure -> error.text = failure.message ?: "请检查参数"; error.visibility = View.VISIBLE })
                }
            }
            card.addView(actionRow(cancel, save), lp(-1, -2, top = 7))
            modal.setOnDismissListener {
                if (editor === modal) editor = null
                if (focusOutput && !closed) renderRules()
            }
            modal.onBackAction = { consumeIme(modal) }
            val height = minOf(c.dp(720), (c.resources.displayMetrics.heightPixels * .85f).toInt())
            if (focusOutput) {
                modal.morphFrom = c.window.decorView.getTag(R.id.native_menu_anchor) as? View
                c.window.decorView.setTag(R.id.native_menu_anchor, null)
            }
            card.minimumHeight = height
            modal.showPanel(card, c.dp(560))
            responsive(modal, card, height)
            val focus = when {
                focusName -> fields["name"]
                focusOutput -> {
                    val key = when (draft.kind) {
                        Kind.REPLACE, Kind.REGEX, Kind.MAPPING, Kind.REARRANGE, Kind.TRANSLITERATE -> "replacement"
                        Kind.INSERT, Kind.USER_INPUT, Kind.REMOVE, Kind.EXTENSION, Kind.RANDOM -> "text"
                        Kind.SERIALIZE -> "start"
                        Kind.PAD -> "width"
                        Kind.DATE -> "date_format"
                        Kind.DELETE -> if (draft.mode == "range") "position" else "count"
                        Kind.CASE, Kind.CLEANUP, Kind.STRIP -> null
                    }
                    key?.let(fields::get) ?: fields["text"] ?: modeChoice
                }
                else -> null
            }
            focus?.post {
                if (!closed && editor === modal && focus.isAttachedToWindow) {
                    if (focus !is EditText) focus.isFocusableInTouchMode = true
                    focus.requestFocus()
                    if (focus is EditText) focus.selectAll()
                    focus.requestRectangleOnScreen(Rect(0, 0, focus.width, focus.height), true)
                }
            }
        }

        private fun rememberLast() {
            runCatching { presetStore.remember(RenamePresetStore.LastUsed(rules.toList(), fileOrder, presetId, presetName)) }
                .onFailure { Toast.makeText(c, "上次规则保存失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
        private fun schedulePreview(persist: Boolean = true) {
            if (!mutable()) return
            if (persist) rememberLast()
            generation++; val version = generation
            latestPlan = null; enabled(execute, false); pageIndex = 0
            debounce?.let { main.removeCallbacks(it) }; previewFuture?.cancel(true); previewFuture = null
            status.text = "正在生成预览…"; status.setTextColor(p.secondary)
            previewHeader.text = "原名 → 新名 · 正在更新（旧结果变淡）"; previewRows.alpha = .4f
            val settings = BatchRenameEngine.Options(rules = rules.toList())
            val order = fileOrder
            val pending = Runnable {
                debounce = null
                if (!mutable() || version != generation) return@Runnable
                previewFuture = io.submit {
                    val outcome = runCatching { BatchRenameEngine.preview(RenameFileOrder.sort(files, order), settings) }
                    main.post {
                        if (mutable() && version == generation) outcome.fold({ plan ->
                            latestPlan = plan; renderPreview(plan)
                        }, { error -> validationError(error.message ?: "无法读取文件，请检查访问权限") })
                    }
                }
            }
            debounce = pending; main.postDelayed(pending, 180)
        }
        private fun validationError(message: String) {
            latestPlan = null; previewRows.alpha = 1f; previewRows.removeAllViews()
            batchErrors.text = message; batchErrors.visibility = View.VISIBLE; pager.visibility = View.GONE
            status.text = "规则有误，已禁止执行"; status.setTextColor(danger); enabled(execute, false)
        }
        private fun renderPreview(plan: BatchRenameEngine.Plan) {
            previewRows.alpha = 1f; previewRows.removeAllViews()
            batchErrors.text = plan.errors.joinToString("\n")
            batchErrors.visibility = if (plan.errors.isEmpty()) View.GONE else View.VISIBLE
            previewHeader.text = if (plan.entries.any { ' ' in it.source.name || ' ' in it.target.name })
                "原名 → 新名 · ${plan.entries.size} 项（短槽为空格）" else "原名 → 新名 · ${plan.entries.size} 项"
            val pages = ((plan.entries.size + pageSize - 1) / pageSize).coerceAtLeast(1)
            pageIndex = pageIndex.coerceIn(0, pages - 1)
            val from = pageIndex * pageSize
            plan.entries.drop(from).take(pageSize).forEachIndexed { offset, entry ->
                val index = from + offset; val invalid = entry.errors.isNotEmpty()
                val row = column().apply {
                    tag = "batch_rename_entry_$index"; setPadding(c.dp(10), c.dp(9), c.dp(10), c.dp(9))
                    background = p.background(p.surface, c.dp(13).toFloat())
                }
                row.addView(PreviewNameView(c, "${index + 1}. 原：", entry.source.name, 13f, p.secondary, false, p.secondary).apply {
                    tag = "batch_rename_before_$index"
                }, lp(-1, -2))
                row.addView(PreviewNameView(c, "新：", entry.target.name, 14f,
                    if (invalid) danger else if (entry.changed) p.blue else p.secondary, entry.changed, p.secondary).apply {
                    tag = "batch_rename_after_$index"
                }, lp(-1, -2, top = 5))
                row.addView(c.label(if (invalid) entry.errors.joinToString("\n") else if (entry.changed) "待重命名" else "名称未改变",
                    12f, if (invalid) danger else p.secondary).apply {
                    tag = "batch_rename_error_$index"; setTextIsSelectable(true)
                }, lp(-1, -2, top = 4))
                previewRows.addView(row, lp(-1, -2, bottom = 6))
            }
            val errors = plan.entries.count { it.errors.isNotEmpty() }
            pager.visibility = if (pages > 1) View.VISIBLE else View.GONE
            pageLabel.text = "${pageIndex + 1} / $pages 页"
            enabled(previous, pageIndex > 0 && !busy); enabled(next, pageIndex < pages - 1 && !busy)
            status.text = when {
                plan.errors.isNotEmpty() || errors > 0 -> "${plan.errors.size} 个批次错误 · $errors 项有错误，暂不可执行"
                plan.changedCount == 0 -> if (rules.none { it.enabled }) "没有启用的规则 · ${files.size} 项未改变" else "${files.size} 项名称均未改变"
                else -> "${plan.changedCount} 项将改名 · ${plan.entries.size - plan.changedCount} 项不变"
            }
            status.setTextColor(if (plan.errors.isNotEmpty() || errors > 0) danger else p.secondary)
            enabled(execute, plan.canExecute && mutable())
        }
        private fun changePage(delta: Int) {
            if (!mutable()) return
            latestPlan?.let { pageIndex += delta; renderPreview(it); previewScroll.scrollTo(0, 0) }
        }
        private fun executePlan() {
            if (!mutable()) return
            val plan = latestPlan?.takeIf { it.canExecute } ?: return
            generation++; debounce?.let { main.removeCallbacks(it) }; debounce = null
            previewFuture?.cancel(true); previewFuture = null
            busy = true; renderRules(); enabled(execute, false); enabled(close, false)
            enabled(previous, false); enabled(next, false)
            dialog.setCancelable(false); hideKeyboard(dialog)
            status.text = "正在安全更新文件名及媒体库，请勿退出…"; progress.visibility = View.VISIBLE
            io.execute {
                val result = try { MediaStoreRename.execute(c.applicationContext, plan) }
                catch (e: Exception) {
                    BatchRenameEngine.Result(false, 0, "执行异常，最终状态未能确认，请刷新核对原目录：${e.message ?: e.javaClass.simpleName}", false, emptyList())
                }
                completionMain.post { finishExecution(result) }
            }
        }
        private fun finishExecution(result: BatchRenameEngine.Result) {
            if (resultDelivered) return
            resultDelivered = true; busy = false; terminal = true; latestPlan = null
            runCatching { onFinished(result) }.onFailure {
                Log.e("BatchRenameDialog", "Batch rename completion callback failed", it)
            }
            if (closed || activity.isFinishing || activity.isDestroyed) return
            renderRules(); dialog.setCancelable(true); enabled(close, true); close.text = "完成"
            execute.text = if (result.success) "已完成" else "执行未完成"; enabled(execute, false)
            progress.visibility = View.GONE
            previewHeader.text = "执行前的计划预览"; previewRows.alpha = .55f
            status.text = if (result.success) "已完成 ${result.renamedCount} 项重命名" else "执行未完成，请核对结果"
            status.setTextColor(if (result.success) p.blue else danger)
            resultText.text = buildString {
                append(result.message)
                if (!result.success && result.locations.isNotEmpty()) {
                    append("\n\n文件当前位置：")
                    result.locations.forEach { append("\n").append(it.source.name).append(" → ").append(it.current?.absolutePath ?: "位置未能确认，请检查原目录") }
                }
            }
            resultText.setTextColor(if (result.success) p.text else danger); resultText.visibility = View.VISIBLE
            previewScroll.scrollTo(0, 0)
        }

        private fun updateOrder() {
            presetRevision++
            orderLabel.text = if (fileOrder.key == RenameFileOrder.Key.CAPTURED)
                "拍摄时间\n缺失时用修改时间" else fileOrder.key.title
            direction.text = if (fileOrder.descending) "降序 ↓" else "升序 ↑"
            direction.contentDescription = "当前${if (fileOrder.descending) "降序" else "升序"}，点击切换"
        }
        private fun sortMenu() {
            if (!mutable() || editor != null) return
            sortFiles.menuAnchor()
            val keys = RenameFileOrder.Key.values()
            menus.show("文件排序 · 编号将按此顺序重算", keys.map { it.title }, keys.indexOf(fileOrder.key)) { index ->
                if (mutable()) {
                    fileOrder = fileOrder.copy(key = keys[index]); updateOrder(); schedulePreview()
                }
            }
        }
        private fun savePreset(renaming: RenamePresetStore.Preset? = null) {
            if (!mutable() || (renaming == null && rules.isEmpty()) || editor != null) return
            val snapshot = rules.toList(); val order = fileOrder
            val modal = MotionDialog(c, R.style.NativeTheme).apply { morphFrom = if (renaming == null) savePreset else loadPreset }
            editor = modal
            val card = column().apply {
                tag = "batch_rename_preset_editor"; setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(14))
                background = p.background(p.page, c.dp(24).toFloat())
            }
            card.addView(c.label(if (renaming == null) "保存规则方案" else "方案改名", 20f, p.text, true), lp(-1, -2, bottom = 12))
            val name = EditText(c).apply {
                tag = "batch_rename_preset_title"; setTextColor(p.text); setHintTextColor(p.secondary)
                textSize = 16f; minHeight = c.dp(52); setSingleLine(true); hint = "例如：照片日期编号"
                inputType = InputType.TYPE_CLASS_TEXT
                background = p.background(p.surface, c.dp(12).toFloat())
                setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10)); setText(renaming?.name ?: presetName)
            }
            card.addView(name, lp(-1, -2))
            note(card, if (renaming == null) "保存 ${snapshot.size} 条规则和${order.title}。同名保存会更新该方案，其余方案保留。" else "仅修改方案名称，原规则内容和排序保留。")
            val error = c.label("", 13f, danger).apply { tag = "batch_rename_preset_error"; visibility = View.GONE }
            card.addView(error, lp(-1, -2, bottom = 8))
            val cancel = action("取消", "preset_cancel") { hideKeyboard(modal); modal.close {} }
            lateinit var save: TextView
            save = action("保存方案", "preset_commit", true) {
                if (mutable()) {
                    val title = name.text.toString().trim()
                    if (title.isEmpty() || title.codePointCount(0, title.length) > 80) {
                        error.text = "请输入 1—80 字的方案名称"; error.visibility = View.VISIBLE
                    } else {
                        enabled(save, false); enabled(cancel, false); name.isEnabled = false; modal.setCancelable(false)
                        io.execute {
                            val result = runCatching { if (renaming == null) presetStore.save(title, snapshot, order) else presetStore.rename(renaming.id, title) }
                            main.post {
                                if (!closed && editor === modal) result.fold({ saved ->
                                    presetRevision++
                                    if (renaming == null || presetId == renaming.id) {
                                        presetId = saved.id; presetName = saved.name; presetLabel.text = "方案 · ${saved.name}"; rememberLast()
                                    }
                                    hideKeyboard(modal); modal.setCancelable(true); modal.close {}
                                    Toast.makeText(c, "已保存：${saved.name}", Toast.LENGTH_SHORT).show()
                                }, { failure ->
                                    enabled(save, true); enabled(cancel, true); name.isEnabled = true; modal.setCancelable(true)
                                    error.text = failure.message ?: "保存失败"; error.visibility = View.VISIBLE
                                })
                            }
                        }
                    }
                }
            }
            card.addView(actionRow(cancel, save), lp(-1, -2, top = 7))
            modal.onBackAction = { !save.isEnabled || consumeIme(modal) }
            modal.setOnDismissListener { if (editor === modal) editor = null }
            modal.showPanel(card, c.dp(460))
            // Wrap-content naming surface: IME repositions it without creating a tall empty panel.
        }
        private fun loadPreset() {
            if (!mutable() || editor != null) return
            val revision = ++presetRevision
            io.execute {
                val loaded = runCatching { presetStore.list() }
                main.post {
                    if (mutable() && editor == null && revision == presetRevision) loaded.fold({ saved ->
                        if (saved.isEmpty()) Toast.makeText(c, "还没有方案，添加规则后点击“保存”命名", Toast.LENGTH_LONG).show()
                        else {
                            loadPreset.menuAnchor()
                            menus.show("载入规则方案", saved.map { "${it.name} · ${it.rules.size} 条" } + "管理已存方案…") { index ->
                                if (mutable()) {
                                    if (index < saved.size) applyPreset(saved[index])
                                    else managePresets(saved)
                                }
                            }
                        }
                    }, { Toast.makeText(c, it.message ?: "载入方案失败", Toast.LENGTH_LONG).show() })
                }
            }
        }
        private fun applyPreset(preset: RenamePresetStore.Preset) {
            if (!mutable()) return
            rules.clear(); rules.addAll(preset.rules); selected = if (rules.isEmpty()) -1 else 0
            fileOrder = preset.order; presetId = preset.id; presetName = preset.name; presetLabel.text = "方案 · ${preset.name}"
            updateOrder(); rulesChanged()
        }
        private fun managePresets(saved: List<RenamePresetStore.Preset>) {
            if (!mutable() || saved.isEmpty()) return
            loadPreset.menuAnchor()
            menus.show("选择要管理的方案", saved.map { it.name }) { index ->
                if (mutable()) {
                    val preset = saved[index]
                    loadPreset.menuAnchor()
                    menus.show(preset.name, listOf("载入 · ${preset.name}", "改名 · ${preset.name}", "删除 · ${preset.name}")) { action ->
                        if (mutable()) {
                            if (action == 0) applyPreset(preset)
                            else if (action == 1) savePreset(preset)
                            else io.execute {
                                val result = runCatching { presetStore.delete(preset.id) }
                                main.post { if (!closed) {
                                    presetRevision++
                                    result.fold({ Toast.makeText(c, "已删除方案：${preset.name}", Toast.LENGTH_SHORT).show() },
                                        { Toast.makeText(c, it.message ?: "删除失败", Toast.LENGTH_LONG).show() })
                                } }
                            }
                        }
                    }
                }
            }
        }
        private fun hideKeyboard(modal: MotionDialog) {
            val target = modal.window?.decorView ?: return
            (c.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(target.windowToken, 0)
            target.clearFocus()
        }
        private fun consumeIme(modal: MotionDialog): Boolean {
            val root = modal.window?.decorView ?: return false
            if (ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) != true) return false
            hideKeyboard(modal); return true
        }
        /** MotionDialog measures once; additionally fit the real viewport after IME/split-screen. */
        private fun responsive(modal: MotionDialog, card: View, preferredHeight: Int) {
            val host = card.parent as? ViewGroup ?: return
            // MotionDialog dim directly calls cancel(), bypassing setCancelable(false).
            host.findViewById<View>(R.id.native_modal_dim)?.setOnClickListener { /* explicit controls/back only */ }
            fun fit(insets: WindowInsetsCompat?) {
                if (closed || modal.layoutFrozen || host.height <= 0) return
                val bars = insets?.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                val gap = c.dp(8)
                val available = (host.height - (bars?.top ?: 0) - maxOf(bars?.bottom ?: 0, ime) - 2 * gap).coerceAtLeast(1)
                val height = minOf(preferredHeight, available)
                val width = minOf(c.dp(if (modal === dialog) 680 else 560), host.width - (bars?.left ?: 0) - (bars?.right ?: 0) - 2 * gap).coerceAtLeast(1)
                val params = card.layoutParams as? FrameLayout.LayoutParams ?: return
                val top = (bars?.top ?: 0) + gap + (available - height) / 2
                val left = ((bars?.left ?: 0) - (bars?.right ?: 0)) / 2
                if (params.height != height || params.width != width || params.topMargin != top || params.leftMargin != left) {
                    params.height = height; params.width = width; params.topMargin = top; params.leftMargin = left
                    card.minimumHeight = 0; card.layoutParams = params
                }
            }
            ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets -> fit(insets); insets }
            host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit(ViewCompat.getRootWindowInsets(host)) }
            host.post { if (!closed) fit(ViewCompat.getRootWindowInsets(host)) }
            ViewCompat.requestApplyInsets(host)
        }
        private fun cleanUp() {
            if (closed) return
            closed = true; generation++
            main.removeCallbacksAndMessages(null) // UI/preview only; completionMain survives.
            debounce = null; previewFuture?.cancel(true); previewFuture = null
            editor?.dispose(); editor = null
            menus.dismiss()
            io.shutdown() // NEVER shutdownNow: an in-flight transaction must finish/roll back.
            if (registered) { activity.application.unregisterActivityLifecycleCallbacks(lifecycle); registered = false }
        }
    }
}
