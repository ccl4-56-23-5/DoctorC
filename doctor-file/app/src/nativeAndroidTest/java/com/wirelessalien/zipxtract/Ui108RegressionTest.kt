package com.wirelessalien.zipxtract

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.nativeui.LiquidGlassView
import com.wirelessalien.zipxtract.nativeui.dp
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 1.0.8 regressions: screen-space toolbar geometry, inherited/composited backdrop,
 * real RecyclerView long-hold/MOVE streams, and the integrated batch-renamer flow.
 * Reflection is confined to fixture installation and observation, never gesture callbacks.
 */
@RunWith(AndroidJUnit4::class)
class Ui108RegressionTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val preferences by lazy { context.getSharedPreferences("native_ui", 0) }
    private var savedPreferences: Map<String, Any?> = emptyMap()
    private val renamePreferences by lazy { context.getSharedPreferences("batch_rename_rules", 0) }
    private var savedRenamePreferences: Map<String, *> = emptyMap<String, Any>()
    private var scenario: ActivityScenario<MainActivity>? = null
    // Only the rename cases bypass startActivitySync; the original six scenarios remain unchanged.
    private var directlyLaunched: MainActivity? = null
    private lateinit var folder: File
    private var downTime = 0L
    private var pointerActive = false
    private val toolbarActions = listOf("新建文件夹", "排序", "更多选项")

    @get:Rule val testTrace = object : TestWatcher() {
        override fun starting(description: Description) {
            Log.i("Ui108Regression", "ENTER ${description.methodName}")
        }
        override fun failed(error: Throwable, description: Description) {
            Log.e("Ui108Regression", "FAIL ${description.methodName}", error)
        }
        override fun finished(description: Description) {
            Log.i("Ui108Regression", "EXIT ${description.methodName}")
        }
    }

    @Before fun setup() {
        savedRenamePreferences = renamePreferences.all.toMap()
        renamePreferences.edit().clear().commit()
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
        )).use { it.readBytes() }
        savedPreferences = preferences.all.filterKeys { it in setOf("sort", "descending", "hidden") }
        preferences.edit().putString("sort", "name").putBoolean("descending", false)
            .putBoolean("hidden", false).commit()
        folder = File(context.getExternalFilesDir(null)!!, "ui108-${System.nanoTime()}")
        check(folder.mkdirs()) { "Could not create isolated fixture" }
    }

    @After fun cleanup() {
        try {
            if (pointerActive && (scenario != null || directlyLaunched != null))
                onActivity { send(it, MotionEvent.ACTION_CANCEL, PointF(1f, 1f)) }
        } finally {
            directlyLaunched?.let { activity ->
                ins.runOnMainSync { if (!activity.isFinishing) activity.finish() }
                ins.runOnMainSync { }
                directlyLaunched = null
            }
            // Stop the rendering Activity before ActivityScenario.close's own idle synchronization.
            if (scenario != null) {
                runCatching { onActivity { it.finish() } }
                ins.runOnMainSync { }
                scenario?.close(); scenario = null
            }
            val editor = preferences.edit().remove("sort").remove("descending").remove("hidden")
            for ((key, value) in savedPreferences) when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
            editor.commit()
            renamePreferences.edit().clear().apply {
                savedRenamePreferences.forEach { (key,value) -> when(value) {
                    is String -> putString(key,value); is Boolean -> putBoolean(key,value)
                    is Int -> putInt(key,value); is Long -> putLong(key,value); is Float -> putFloat(key,value)
                } }
            }.commit()
            if (::folder.isInitialized) {
                val parent = context.getExternalFilesDir(null)!!.canonicalFile
                check(folder.canonicalFile.parentFile == parent && folder.name.startsWith("ui108-"))
                folder.deleteRecursively()
            }
        }
    }

    private fun files(count: Int = 80): List<File> = (0 until count).map {
        File(folder, "file_${it.toString().padStart(3, '0')}.txt").apply { writeText("payload-$it") }
    }
    private fun launch(expectedFiles: Int) {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_DIRECTORY)
            .putExtra(MainActivity.EXTRA_DIRECTORY_PATH, folder.path))
        await("directory rows loaded") {
            var ready = false
            onActivity { a ->
                ready = items(a).count { row -> row.file != null } == expectedFiles &&
                    field(a, "pageGhost") == null && a.recycler.findViewHolderForAdapterPosition(1) != null
            }
            ready
        }
        settle(160)
    }
    /** Barrier only: BlurView/Glide may continuously schedule frames, so global Looper idle is not a precondition. */
    private fun settle(ms: Long = 120) { SystemClock.sleep(ms); ins.runOnMainSync { } }
    private fun onActivity(action: (MainActivity) -> Unit) {
        var failure: Throwable? = null
        // ActivityScenario's background-thread onActivity branch also waits for global idle. Calling
        // its main-thread branch inside this queue barrier retains lifecycle checks without that wait.
        ins.runOnMainSync {
            try {
                val direct = directlyLaunched
                if (direct != null) {
                    check(!direct.isFinishing && !direct.isDestroyed) { "Rename Activity is no longer active" }
                    action(direct)
                } else checkNotNull(scenario).onActivity { action(it) }
            }
            catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
    private fun await(message: String, timeout: Long = 8_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            ins.runOnMainSync { }
            if (condition()) return
            SystemClock.sleep(40)
        }
        throw AssertionError("Timed out: $message")
    }
    private fun field(a: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a)
    private fun setField(a: MainActivity, name: String, value: Any) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(a, value)
    @Suppress("UNCHECKED_CAST")
    private fun items(a: MainActivity) = field(a, "listItems") as MutableList<MainActivity.Item>
    @Suppress("UNCHECKED_CAST")
    private fun selected(a: MainActivity) = (field(a, "selected") as Set<String>).toSet()
    private fun refresh(a: MainActivity) = MainActivity::class.java
        .getDeclaredMethod("refreshViews", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(a, false)
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
    private fun toolbarButton(a: MainActivity, description: String): LiquidGlassView {
        val found = descendants(a.window.decorView).filter {
            it is LiquidGlassView && it.contentDescription?.toString() == description && it.isShown
        }
        assertEquals("one visible toolbar action: $description", 1, found.size)
        return found.single() as LiquidGlassView
    }
    private fun bounds(view: View): Rect {
        val position = IntArray(2); view.getLocationOnScreen(position)
        return Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }
    private fun assertFixed(expected: Rect, actual: Rect, description: String) {
        assertTrue("$description moved: $expected -> $actual", abs(expected.left - actual.left) <= 1 &&
            abs(expected.top - actual.top) <= 1 && abs(expected.right - actual.right) <= 1 &&
            abs(expected.bottom - actual.bottom) <= 1)
    }
    private fun screenshot(name: String): Bitmap {
        val bitmap = ins.uiAutomation.takeScreenshot() ?: throw AssertionError("Hardware screenshot unavailable")
        val output = context.getExternalFilesDir("qa108")!!
        check(output.isDirectory || output.mkdirs())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test fun toolbarActionsStayAtInitialScreenCoordinatesAndBelowStatusBar() {
        files(); launch(80)
        val initial = linkedMapOf<String, Rect>()
        onActivity { a ->
            assertEquals(View.INVISIBLE, a.findViewById<View>(R.id.native_top_fade).visibility)
            for (description in toolbarActions) {
                val button = toolbarButton(a, description)
                initial[description] = bounds(button)
                assertTrue("initial icon inherits page; never raw-target optics", button.inheritsBackdrop)
                assertFalse("initial toolbar must not have glass circles", button.isGlassSurfaceVisible)
            }
        }
        screenshot("toolbar-initial").recycle()
        // Include the former expanded/compact handoff dead zone, not merely start/end.
        var previous = 0
        for (offset in listOf(28, 56, 72, 88, 110, 200, 700, 1400, 0)) {
            onActivity { a ->
                if (offset == 0) a.recycler.scrollToPosition(0)
                else a.recycler.scrollBy(0, a.dp(offset - previous))
            }
            previous = offset; settle()
            onActivity { a ->
                val safeTop = ViewCompat.getRootWindowInsets(a.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top
                    ?: throw AssertionError("Missing window insets")
                for (description in toolbarActions) {
                    val button = toolbarButton(a, description)
                    val actual = bounds(button)
                    assertFixed(initial.getValue(description), actual, description)
                    assertTrue("$description entered status bar: $actual safeTop=$safeTop", actual.top >= safeTop)
                    val visible = Rect(); assertTrue(button.getGlobalVisibleRect(visible))
                    assertEquals("$description clipped", actual, visible)
                    assertEquals(1f, button.scaleX, .001f); assertEquals(1f, button.scaleY, .001f)
                    assertTrue("toolbar must inherit current composition", button.inheritsBackdrop)
                    if (offset >= 110) assertTrue("scrolled toolbar circle missing", button.isGlassSurfaceVisible)
                    if (offset == 0) assertFalse("glass remains after return to top", button.isGlassSurfaceVisible)
                }
            }
        }
    }

    @Test fun circlesCompositeOverTheCurrentBlurInsteadOfCuttingAHoleToRawContent() {
        files(); launch(80)
        var fixture: View? = null
        var target: BlurTarget? = null
        val buttons = mutableListOf<LiquidGlassView>()
        val buttonBounds = mutableListOf<Rect>()
        var composed: Bitmap? = null
        var withoutButtons: Bitmap? = null
        try {
            onActivity { a ->
                a.recycler.scrollBy(0, a.dp(350))
                target = field(a, "target") as BlurTarget
                fixture = object : View(a) {
                    private val paint = Paint()
                    override fun onDraw(canvas: Canvas) {
                        for (y in 0 until height step 4) {
                            paint.color = if ((y / 4) % 2 == 0) Color.MAGENTA else Color.GREEN
                            canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 4).toFloat(), paint)
                        }
                    }
                }
                target!!.addView(fixture, FrameLayout.LayoutParams(-1, -1))
                for (description in toolbarActions) {
                    val button = toolbarButton(a, description)
                    assertTrue(button.inheritsBackdrop); assertTrue(button.isGlassSurfaceVisible)
                    buttons += button; buttonBounds += bounds(button)
                }
            }
            settle(220); composed = screenshot("toolbar-stripes-composited")
            onActivity { buttons.forEach { it.visibility = View.INVISIBLE } }
            settle(180); withoutButtons = screenshot("toolbar-stripes-underlay")
            val foreground = composed!!; val background = withoutButtons!!
            assertEquals(foreground.width, background.width); assertEquals(foreground.height, background.height)
            var difference = 0L; var samples = 0
            var blurDifference = 0L; var blurSamples = 0
            for (rect in buttonBounds) {
                val radius = rect.width() / 2f
                // Annular points outside the 25dp icon and inside the rim/shadow.
                for (side in listOf(-1f, 1f)) for (fraction in listOf(-.35f, -.15f, .15f, .35f)) {
                    val x = (rect.exactCenterX() + side * radius * .78f).roundToInt()
                    val y = (rect.exactCenterY() + fraction * radius).roundToInt()
                    val one = foreground.getPixel(x, y); val two = background.getPixel(x, y)
                    difference += abs(Color.red(one) - Color.red(two)) + abs(Color.green(one) - Color.green(two)) +
                        abs(Color.blue(one) - Color.blue(two)); samples += 3
                    val adjacent = background.getPixel(x, y + 4)
                    blurDifference += abs(Color.red(two) - Color.red(adjacent)) +
                        abs(Color.green(two) - Color.green(adjacent)) + abs(Color.blue(two) - Color.blue(adjacent))
                    blurSamples += 3
                }
            }
            assertTrue("circles expose sharp raw stripes instead of source-over blur; mean RGB delta=${difference.toFloat() / samples}",
                difference.toFloat() / samples <= 40f)
            assertTrue("underlay itself is not blurred/faded; stripe mean RGB delta=${blurDifference.toFloat() / blurSamples}",
                blurDifference.toFloat() / blurSamples < 20f)
            // Prove the high-frequency fixture actually reached the raw target outside the fade.
            lateinit var sourceBounds: Rect
            onActivity { sourceBounds = bounds(target!!) }
            val x = sourceBounds.centerX().coerceIn(0, foreground.width - 1)
            val y = (sourceBounds.top + sourceBounds.height() / 2).coerceIn(0, foreground.height - 6)
            val one = foreground.getPixel(x, y); val two = foreground.getPixel(x, y + 4)
            val rawDelta = abs(Color.red(one) - Color.red(two)) + abs(Color.green(one) - Color.green(two)) +
                abs(Color.blue(one) - Color.blue(two))
            assertTrue("stripe fixture was not visible in raw target: $rawDelta", rawDelta > 500)
        } finally {
            if (scenario != null) onActivity {
                buttons.forEach { button -> button.visibility = View.VISIBLE }
                fixture?.let { view -> target?.removeView(view) }
            }
            composed?.recycle(); withoutButtons?.recycle()
        }
    }

    private fun prepareListSelection() {
        files(); launch(80)
        onActivity { a ->
            (a.recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(1, a.dp(125))
        }
        settle()
    }
    private fun point(a: MainActivity, position: Int): PointF {
        val row = a.recycler.findViewHolderForAdapterPosition(position)?.itemView
            ?: throw AssertionError("Fixture row $position is not visible")
        return PointF(row.left + row.width * .45f, row.top + row.height * .5f)
    }
    private fun send(a: MainActivity, action: Int, point: PointF) {
        if (action == MotionEvent.ACTION_DOWN) { downTime = SystemClock.uptimeMillis(); pointerActive = true }
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0).also {
            it.source = InputDevice.SOURCE_TOUCHSCREEN
            try { a.recycler.dispatchTouchEvent(it) } finally { it.recycle() }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pointerActive = false
    }
    private fun hold(position: Int) {
        onActivity { send(it, MotionEvent.ACTION_DOWN, point(it, position)) }
        settle(ViewConfiguration.getLongPressTimeout().toLong() + 130)
    }
    private fun move(position: Int) {
        onActivity { send(it, MotionEvent.ACTION_MOVE, point(it, position)) }; settle(50)
    }
    private fun expectSelection(positions: List<Int>) {
        onActivity { a ->
            assertEquals("range includes only selectable file keys", positions.mapNotNull { items(a)[it].file?.path }.toSet(), selected(a))
        }
    }
    private fun rangeGesture(anchor: Int, far: Int, near: Int, retained: List<Int> = emptyList()) {
        hold(anchor); expectSelection(listOf(anchor) + retained)
        move(far); expectSelection((anchor..far).toList() + retained)
        move(near); expectSelection((anchor..near).toList() + retained)
        onActivity { send(it, MotionEvent.ACTION_UP, point(it, near)) }
        settle(); expectSelection((anchor..near).toList() + retained)
    }

    @Test fun listLongHoldExpandsThenContractsWithoutAnExtraClickOnRelease() {
        prepareListSelection()
        // Seed an out-of-range selection through a separate genuine hold/release, not state mutation.
        hold(4)
        onActivity { send(it, MotionEvent.ACTION_UP, point(it, 4)) }
        settle(); expectSelection(listOf(4))
        rangeGesture(1, 3, 2, retained = listOf(4))
    }

    @Test fun gridLongHoldCrossesColumnsAndRestoresContractedRange() {
        val imageFiles = files(30)
        // Use real PNGs but no MediaScanner/global catalog, making this a deterministic gesture test.
        val pixel = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val pictures = imageFiles.map { source ->
            File(folder, source.nameWithoutExtension + ".png").also { output ->
                output.outputStream().use { pixel.compress(Bitmap.CompressFormat.PNG, 100, it) }
                check(source.delete())
            }
        }
        pixel.recycle(); launch(30)
        onActivity { a ->
            (field(a, "loading") as? Job)?.cancel()
            setField(a, "page", MainActivity.Page.CATEGORY); setField(a, "category", "图片")
            items(a).apply {
                clear(); add(MainActivity.Item("", kind = "header"))
                add(MainActivity.Item("今天", kind = "date", groupKey = "ui108-date-0", count = 2))
                addAll(pictures.take(2).map { MainActivity.Item(it.name, file = it, kind = "media") })
                add(MainActivity.Item("昨天", kind = "date", groupKey = "ui108-date-1", count = pictures.size - 2))
                addAll(pictures.drop(2).map { MainActivity.Item(it.name, file = it, kind = "media") })
            }
            refresh(a)
        }
        settle()
        onActivity { a ->
            assertTrue("fixture must exercise actual GridLayoutManager", a.recycler.layoutManager is GridLayoutManager)
            (a.recycler.layoutManager as GridLayoutManager).scrollToPositionWithOffset(2, a.dp(125))
        }
        settle(); rangeGesture(2, 6, 3)
    }

    @Test fun edgeHoldAutoScrollsAndCancelStopsFurtherSelectionAndScrolling() {
        prepareListSelection(); hold(1)
        var before = 0
        onActivity { a ->
            before = a.recycler.computeVerticalScrollOffset()
            send(a, MotionEvent.ACTION_MOVE, PointF(a.recycler.width * .45f, a.recycler.height - a.dp(5).toFloat()))
        }
        settle(700)
        var stopped = 0; var chosen = emptySet<String>()
        onActivity { a ->
            assertTrue("edge hold did not scroll", a.recycler.computeVerticalScrollOffset() > before)
            assertTrue("edge movement did not extend selection", selected(a).size > 1)
            send(a, MotionEvent.ACTION_CANCEL, PointF(1f, 1f))
            stopped = a.recycler.computeVerticalScrollOffset(); chosen = selected(a)
        }
        settle(250)
        onActivity { a ->
            assertEquals("autoscroll survived CANCEL", stopped, a.recycler.computeVerticalScrollOffset())
            send(a, MotionEvent.ACTION_MOVE, PointF(a.recycler.width * .45f, a.dp(150).toFloat()))
            assertEquals("cancelled gesture still changes selected keys", chosen, selected(a))
        }
    }

    @Test fun ordinaryListScrollNeverStartsSelection() {
        prepareListSelection()
        onActivity { a ->
            val start = point(a, 2)
            send(a, MotionEvent.ACTION_DOWN, start)
            for (step in 1..5) send(a, MotionEvent.ACTION_MOVE, PointF(start.x, start.y - a.dp(step * 18)))
            send(a, MotionEvent.ACTION_UP, PointF(start.x, start.y - a.dp(90)))
        }
        settle(); onActivity { assertTrue("ordinary scrolling selected files", selected(it).isEmpty()) }
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        ins.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    /** Public WindowInspector roots only, never hidden WindowManager internals or engine callbacks. */
    private fun shownRoots(): List<View> = if (Build.VERSION.SDK_INT >= 29)
        WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.isShown }
    else throw AssertionError("WindowInspector-based dialog tests require Android 29+")
    private fun <T> withDialogView(tag: String, action: (View) -> T): T = onMain {
        val roots = shownRoots().filter { root -> descendants(root).any { it.tag == "batch_rename_panel" } }
        assertEquals("one attached visible batch rename window", 1, roots.size)
        val views = descendants(roots.single()).filter { it.tag == tag }
        assertEquals("one real dialog view tagged $tag", 1, views.size)
        val view = views.single()
        assertTrue("dialog view detached: $tag", view.isAttachedToWindow)
        action(view)
    }
    private fun dialogText(tag: String): String = withDialogView(tag) { (it as TextView).text.toString() }
    private fun dialogEnabled(tag: String): Boolean = withDialogView(tag) { it.isEnabled }
    private fun clickDialog(tag: String) = withDialogView(tag) {
        assertTrue("dialog action not shown: $tag", it.isShown)
        assertTrue("dialog action disabled: $tag", it.isEnabled)
        assertTrue("dialog action not clickable: $tag", it.performClick())
    }
    private fun revealDialog(tag: String) = withDialogView(tag) {
        it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true)
    }
    private fun setText(tag: String, value: String) = withDialogView(tag) {
        val editor = it as EditText
        editor.requestRectangleOnScreen(Rect(0, 0, editor.width, editor.height), true)
        editor.setText(value) // Executes the production TextWatcher/debounce/preview path.
    }
    private fun clickDescription(description: String) = onMain {
        val matches = shownRoots().flatMap { descendants(it) }.filter {
            it.isShown && it.isEnabled && it.contentDescription?.toString() == description
        }
        assertEquals("one real visible action: $description", 1, matches.size)
        assertTrue("action not clickable: $description", matches.single().performClick())
    }
    private fun assertShortDirectoryToolbarPixels(evidencePrefix: String) {
        val regions = mutableListOf<Triple<String, String, Rect>>()
        // Activity RESUMED precedes the platform's SurfaceControl launch animation. A screenshot
        // can still be translated/rounded while getLocationOnScreen already reports final bounds.
        // Await actual visible icon pixels with a bounded deadline; a truly missing icon still fails.
        fun darkCount(frame: Bitmap, area: Rect): Int {
            if (area.left < 0 || area.top < 0 || area.right > frame.width || area.bottom > frame.height) return 0
            var count = 0
            for (y in area.top until area.bottom) for (x in area.left until area.right) {
                val pixel = frame.getPixel(x, y)
                if (Color.alpha(pixel) > 200 && (299 * Color.red(pixel) + 587 * Color.green(pixel) + 114 * Color.blue(pixel)) / 1000 < 180) count++
            }
            return count
        }
        var captured: Bitmap? = null
        val deadline = SystemClock.uptimeMillis() + 4000
        while (true) {
            regions.clear()
            onActivity { a ->
                val side = a.dp(25).coerceAtLeast(1)
                for ((description, stem) in listOf("返回" to "back", "新建文件夹" to "new-folder",
                    "排序" to "sort", "更多选项" to "more")) {
                    val button = bounds(toolbarButton(a, description))
                    val left = button.centerX() - side / 2
                    val top = button.centerY() - side / 2
                    regions += Triple(description, stem, Rect(left, top, left + side, top + side))
                }
            }
            captured = screenshot("$evidencePrefix-two-file-toolbar")
            if (regions.all { darkCount(captured, it.third) > 10 } || SystemClock.uptimeMillis() >= deadline) break
            captured.recycle(); settle(80)
        }
        val frame = captured!!
        try {
            val counts = mutableListOf<Pair<String, Int>>()
            // Persist all four actual screen crops before asserting any count, so a blank first
            // icon cannot prevent evidence for the remaining three from being collected.
            for ((description, stem, area) in regions) {
                assertTrue("$description icon sample outside viewport: $area",
                    area.left >= 0 && area.top >= 0 && area.right <= frame.width && area.bottom <= frame.height)
                var darkPixels = 0
                for (y in area.top until area.bottom) for (x in area.left until area.right) {
                    val pixel = frame.getPixel(x, y)
                    val luminance = (299 * Color.red(pixel) + 587 * Color.green(pixel) + 114 * Color.blue(pixel)) / 1000
                    if (Color.alpha(pixel) > 200 && luminance < 180) darkPixels++
                }
                val crop = Bitmap.createBitmap(frame, area.left, area.top, area.width(), area.height())
                try {
                    File(context.getExternalFilesDir("qa108")!!, "$evidencePrefix-two-file-icon-$stem.png")
                        .outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { crop.recycle() }
                counts += description to darkPixels
                Log.i("Ui108Regression", "TOOLBAR_PIXELS $evidencePrefix $description dark=$darkPixels area=$area")
            }
            for ((description, darkPixels) in counts) {
                assertTrue("two-file $description icon is blank/clipped in screen pixels: dark=$darkPixels (expected >10)",
                    darkPixels > 10)
            }
        } finally { frame.recycle() }
    }
    /** startActivitySync waits for global idle before returning; keep the real two-file fixture. */
    private fun launchRenameDirectory(evidencePrefix: String) {
        Log.i("Ui108Regression", "RENAME launch async: two files, no padding or synchronous idle")
        onMain {
            context.startActivity(Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DIRECTORY)
                .putExtra(MainActivity.EXTRA_DIRECTORY_PATH, folder.path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        await("two-file rename Activity resumed") {
            directlyLaunched = onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull {
                        !it.isFinishing && !it.isDestroyed &&
                            it.intent.getStringExtra(MainActivity.EXTRA_DIRECTORY_PATH) == folder.path
                    }
            }
            directlyLaunched != null
        }
        await("two-file rename directory rows loaded") {
            var ready = false
            onActivity { a ->
                ready = items(a).count { it.file != null } == 2 && field(a, "pageGhost") == null &&
                    a.recycler.findViewHolderForAdapterPosition(1) != null && a.window.decorView.hasWindowFocus()
            }
            ready
        }
        await("two-file initial bare toolbar icons visible") {
            runCatching {
                onActivity { a ->
                    val controls = field(a, "topControls") as View
                    assertTrue("two-file toolbar host hidden", controls.isShown && controls.alpha >= .99f)
                    for (description in listOf("返回") + toolbarActions) {
                        val button = toolbarButton(a, description)
                        assertTrue("two-file toolbar button transparent: $description", button.alpha >= .99f)
                        assertTrue("two-file toolbar icon hidden: $description",
                            button.childCount > 0 && button.getChildAt(0).isShown && button.getChildAt(0).alpha >= .99f)
                        assertTrue("two-file toolbar not inheriting backdrop", button.inheritsBackdrop)
                        assertFalse("two-file initial toolbar must be bare icons", button.isGlassSurfaceVisible)
                    }
                }
                true
            }.getOrDefault(false)
        }
        settle(160)
        assertShortDirectoryToolbarPixels(evidencePrefix)
        Log.i("Ui108Regression", "RENAME launch ready: two real rows and four pixel-verified bare toolbar icons")
    }
    private fun openRename(evidencePrefix: String) {
        assumeTrue("public WindowInspector-based rename UI tests require Android 29+", Build.VERSION.SDK_INT >= 29)
        files(2); launchRenameDirectory(evidencePrefix)
        hold(1)
        onActivity { send(it, MotionEvent.ACTION_UP, point(it, 1)) }
        settle(); expectSelection(listOf(1))
        onActivity { a ->
            val second = point(a, 2)
            send(a, MotionEvent.ACTION_DOWN, second); send(a, MotionEvent.ACTION_UP, second)
        }
        settle(); expectSelection(listOf(1, 2))
        clickDescription("批量重命名")
        await("rename panel opened") { runCatching { dialogText("batch_rename_status"); true }.getOrDefault(false) }
    }

    /** Views of the modal rule editor, which is its own window (no batch_rename_panel tag). */
    private fun <T> withEditorView(tag: String, action: (View) -> T): T = onMain {
        val views = shownRoots().flatMap { descendants(it) }.filter { it.tag == tag }
        assertEquals("one attached editor view tagged $tag", 1, views.size)
        val view = views.single()
        assertTrue("editor view detached: $tag", view.isAttachedToWindow)
        action(view)
    }
    private fun selectRuleKind(title: String) {
        withEditorView("batch_rename_rule_kind") { it.performClick() }
        settle(400)
        onMain {
            val row = shownRoots().flatMap { descendants(it) }.filterIsInstance<TextView>()
                .firstOrNull { it.text.toString() == title } ?: throw AssertionError("rule choice missing: $title")
            assertTrue((row.parent as View).performClick())
        }
        settle(400)
    }
    private fun setEditorText(tag: String, value: String) = withEditorView(tag) {
        val editor = it as EditText
        editor.requestRectangleOnScreen(Rect(0, 0, editor.width, editor.height), true)
        editor.setText(value)
    }
    private fun clickEditor(tag: String) = withEditorView(tag) {
        assertTrue("editor action not clickable: $tag", it.performClick())
    }
    /** Adds one rule through the real add -> editor -> save flow of the 1.0.9 rule queue. */
    private fun addInsertRule(text: String) {
        clickDialog("batch_rename_add")
        await("rule editor opened") { runCatching { withEditorView("batch_rename_rule_text") { true }; true }.getOrDefault(false) }
        // This older regression deliberately exercises a prefix; the UI now defaults to suffix.
        withEditorView("batch_rename_rule_mode") { it.performClick() }; settle(400)
        onMain {
            val label = shownRoots().flatMap { descendants(it) }.filterIsInstance<TextView>().first { it.text.toString() == "作为前缀" }
            assertTrue((label.parent as View).performClick())
        }; settle(400)
        setEditorText("batch_rename_rule_text", text)
        clickEditor("batch_rename_rule_save"); settle(200)
    }

    @Test fun renameConflictingPreviewDisablesExecutionAndCancelLeavesSourcesUntouched() {
        openRename("rename-conflict")
        clickDialog("batch_rename_add")
        await("rule editor opened") { runCatching { withEditorView("batch_rename_rule_kind") { true }; true }.getOrDefault(false) }
        selectRuleKind("正则表达式"); settle(200)
        await("regex fields shown") { runCatching { withEditorView("batch_rename_rule_text") { true }; true }.getOrDefault(false) }
        setEditorText("batch_rename_rule_text", "^.+$")
        setEditorText("batch_rename_rule_replacement", "same")
        clickEditor("batch_rename_rule_save"); settle(200)
        await("rule row added") { runCatching { dialogText("batch_rename_rule_description_0").contains("same") }.getOrDefault(false) }
        await("conflicting duplicate preview") {
            runCatching {
                dialogText("batch_rename_after_0").contains("same.txt") &&
                    dialogText("batch_rename_after_1").contains("same.txt") &&
                    !dialogEnabled("batch_rename_execute") &&
                    dialogText("batch_rename_error_0").contains("相同目标") &&
                    dialogText("batch_rename_error_1").contains("相同目标")
            }.getOrDefault(false)
        }
        assertFalse("conflicting rename execution must be disabled", dialogEnabled("batch_rename_execute"))
        revealDialog("batch_rename_preview"); settle(120)
        screenshot("rename-conflict-preview").recycle()
        assertEquals(setOf("file_000.txt", "file_001.txt"), folder.listFiles()!!.map { it.name }.toSet())
        ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); settle(220)
        await("conflicting preview cancelled") {
            onMain { shownRoots().none { root -> descendants(root).any { it.tag == "batch_rename_panel" } } }
        }
        assertEquals("payload-0", File(folder, "file_000.txt").readText())
        assertEquals("payload-1", File(folder, "file_001.txt").readText())
        assertEquals(2, folder.listFiles()!!.size)
    }

    @Test fun successfulRenamePreservesContentsAndRefreshesActualActivityRows() {
        openRename("rename-success")
        addInsertRule("renamed_")
        await("rule queue shows the prefix rule") {
            runCatching { dialogText("batch_rename_rule_description_0").contains("renamed_") }.getOrDefault(false)
        }
        await("valid preview enabled") {
            runCatching { dialogText("batch_rename_after_0").contains("renamed_file_000.txt") &&
                dialogText("batch_rename_after_1").contains("renamed_file_001.txt") &&
                dialogEnabled("batch_rename_execute") }.getOrDefault(false)
        }
        revealDialog("batch_rename_preview"); settle(120)
        screenshot("rename-valid-preview").recycle()
        clickDialog("batch_rename_execute")
        val expected = setOf("renamed_file_000.txt", "renamed_file_001.txt")
        await("rename completed and activity reloaded", 12_000) {
            var names = emptySet<String>(); var chosen = setOf("not-yet-cleared")
            onActivity { a -> names = items(a).mapNotNull { it.file?.name }.toSet(); chosen = selected(a) }
            names == expected && chosen.isEmpty() && folder.listFiles()!!.map { it.name }.toSet() == expected
        }
        // The dialog intentionally retains its final result until the user chooses 完成.
        clickDialog("batch_rename_close"); settle(220)
        await("rename result dialog closed") {
            onMain { shownRoots().none { root -> descendants(root).any { it.tag == "batch_rename_panel" } } }
        }
        assertEquals("payload-0", File(folder, "renamed_file_000.txt").readText())
        assertEquals("payload-1", File(folder, "renamed_file_001.txt").readText())
        assertFalse(File(folder, "file_000.txt").exists()); assertFalse(File(folder, "file_001.txt").exists())
        onActivity { a ->
            val displayed = descendants(a.recycler).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("new name absent from rendered row", displayed.contains("renamed_file_000.txt"))
            assertTrue("new name absent from rendered row", displayed.contains("renamed_file_001.txt"))
        }
        screenshot("rename-success-refreshed").recycle()
    }
}
