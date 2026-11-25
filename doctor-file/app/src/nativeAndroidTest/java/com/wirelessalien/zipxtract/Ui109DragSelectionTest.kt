package com.wirelessalien.zipxtract

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.nativeui.DragSelectionController
import com.wirelessalien.zipxtract.nativeui.dp
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

/**
 * 1.0.9 regression for the reported "long press selects one row and dragging cannot reach the
 * other rows" defect.
 *
 * Unlike the 1.0.8 tests, every event here is injected through the real input pipeline with SCREEN
 * coordinates (uiAutomation.injectInputEvent), so the window, decor view, toolbar/dock overlays and
 * RecyclerView all take part. The two historical causes are covered explicitly:
 *  1. a pre-long-press pointer drift inside a wide row, which used to disable the controller while
 *     the row still reported a long click; and
 *  2. a MOVE/UP that arrives while adapter positions are temporarily NO_POSITION after a
 *     selection refresh, which used to drop the endpoint permanently.
 */
@RunWith(AndroidJUnit4::class)
class Ui109DragSelectionTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val preferences by lazy { context.getSharedPreferences("native_ui", 0) }
    private var savedPreferences: Map<String, Any?> = emptyMap()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var folder: File
    private var downTime = 0L
    private var pointerActive = false
    private val slop by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }

    @get:Rule val trace = object : TestWatcher() {
        override fun starting(description: Description) { Log.i("Ui109Drag", "ENTER ${description.methodName}") }
        override fun failed(error: Throwable, description: Description) { Log.e("Ui109Drag", "FAIL ${description.methodName}", error) }
        override fun finished(description: Description) { Log.i("Ui109Drag", "EXIT ${description.methodName}") }
    }

    @Before fun setup() {
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
        )).use { it.readBytes() }
        savedPreferences = preferences.all.filterKeys { it in setOf("sort", "descending", "hidden") }
        preferences.edit().putString("sort", "name").putBoolean("descending", false)
            .putBoolean("hidden", false).commit()
        folder = File(context.getExternalFilesDir(null)!!, "ui109-${System.nanoTime()}")
        check(folder.mkdirs()) { "Could not create isolated fixture" }
    }

    @After fun cleanup() {
        try {
            if (pointerActive && scenario != null) runCatching { inject(MotionEvent.ACTION_CANCEL, 1f, 1f) }
        } finally {
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
            if (::folder.isInitialized) {
                val parent = context.getExternalFilesDir(null)!!.canonicalFile
                check(folder.canonicalFile.parentFile == parent && folder.name.startsWith("ui109-"))
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

    private fun settle(ms: Long = 120) { SystemClock.sleep(ms); ins.runOnMainSync { } }

    private fun onActivity(action: (MainActivity) -> Unit) {
        var failure: Throwable? = null
        ins.runOnMainSync { try { checkNotNull(scenario).onActivity { action(it) } } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }

    private fun <T> onMain(action: (MainActivity) -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        onActivity { a -> try { value = action(a) } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
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

    private fun controller(a: MainActivity) = field(a, "dragSelection") as DragSelectionController

    private fun refresh(a: MainActivity) = MainActivity::class.java
        .getDeclaredMethod("refreshViews", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(a, false)

    /** Real screen coordinates of a visible row, so injection includes the whole window chain. */
    private fun screenPoint(position: Int): PointF = onMain { a ->
        val row = a.recycler.findViewHolderForAdapterPosition(position)?.itemView
            ?: throw AssertionError("Fixture row $position is not visible")
        val location = IntArray(2); row.getLocationOnScreen(location)
        PointF(location[0] + row.width * .45f, location[1] + row.height * .5f)
    }

    private fun assertInsideRow(position: Int, x: Float) = onActivity { a ->
        val row = a.recycler.findViewHolderForAdapterPosition(position)?.itemView
            ?: throw AssertionError("Fixture row $position is not visible")
        val location = IntArray(2); row.getLocationOnScreen(location)
        assertTrue("drift left the row horizontally", x > location[0] + 2 && x < location[0] + row.width - 2)
    }

    private fun inject(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) { downTime = SystemClock.uptimeMillis(); pointerActive = true }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            check(ins.uiAutomation.injectInputEvent(event, true)) { "input injection rejected action=$action" }
        } finally { event.recycle() }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pointerActive = false
    }

    private fun holdForLongPress() = Thread.sleep(ViewConfiguration.getLongPressTimeout() + 220L)

    private fun prepareList() {
        files(); launch(80)
        onActivity { a -> (a.recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(1, a.dp(125)) }
        settle()
    }

    private fun expectSelection(positions: List<Int>) = onActivity { a ->
        assertEquals(positions.mapNotNull { items(a)[it].file?.path }.toSet(), selected(a))
    }

    /** Adapter indices of the currently selected files, ascending. */
    private fun selectedIndices(): List<Int> = onMain { a ->
        val chosen = selected(a)
        items(a).mapIndexedNotNull { index, item -> item.file?.path?.takeIf { it in chosen }?.let { index } }
    }

    /**
     * The gesture must produce a CONTIGUOUS run that still starts at the anchor row and ends at the
     * row under the finger. Positions deliberately avoid the auto-scroll edge zone so the expected
     * end is exact rather than a function of frame timing.
     */
    private fun assertContinuousRange(anchor: Int, expectedEnd: Int) {
        val indices = selectedIndices()
        assertEquals("selected rows should be the whole range $anchor..$expectedEnd", (anchor..expectedEnd).toList(), indices)
    }

    @Test
    fun horizontalDriftInsideAWideRowStillStartsAndExtendsDragSelection() {
        prepareList()
        val start = screenPoint(1)
        inject(MotionEvent.ACTION_DOWN, start.x, start.y)
        Thread.sleep(70)
        // 1.1 * touch slop sideways, zero vertical movement: RecyclerView must not scroll and the
        // row must still long-press, exactly the situation that used to leave only one row selected.
        val driftX = start.x + slop * 1.1f
        inject(MotionEvent.ACTION_MOVE, driftX, start.y)
        assertInsideRow(1, driftX)
        holdForLongPress()
        onActivity { a -> assertTrue("long press did not begin drag selection", controller(a).isSelecting) }
        assertContinuousRange(1, 1)
        val far = screenPoint(4)
        inject(MotionEvent.ACTION_MOVE, far.x, far.y)
        settle(90)
        inject(MotionEvent.ACTION_UP, far.x, far.y)
        settle(200)
        assertContinuousRange(1, 4)
    }

    @Test
    fun diagonalDriftBelowSlopOnBothAxesStillStartsDragSelection() {
        prepareList()
        val start = screenPoint(1)
        inject(MotionEvent.ACTION_DOWN, start.x, start.y)
        Thread.sleep(70)
        inject(MotionEvent.ACTION_MOVE, start.x + slop * .8f, start.y + slop * .8f)
        holdForLongPress()
        onActivity { a -> assertTrue("diagonal drift prevented drag selection", controller(a).isSelecting) }
        val far = screenPoint(3)
        inject(MotionEvent.ACTION_MOVE, far.x, far.y)
        settle(90)
        inject(MotionEvent.ACTION_UP, far.x, far.y)
        settle(200)
        assertContinuousRange(1, 3)
    }

    @Test
    fun singleCrossRowMoveFollowedImmediatelyByUpKeepsTheEndpoint() {
        prepareList()
        val start = screenPoint(1)
        inject(MotionEvent.ACTION_DOWN, start.x, start.y)
        holdForLongPress()
        onActivity { a -> assertTrue(controller(a).isSelecting) }
        val far = screenPoint(5)
        // No settle between MOVE and UP: the selection refresh may still be awaiting layout.
        inject(MotionEvent.ACTION_MOVE, far.x, far.y)
        inject(MotionEvent.ACTION_UP, far.x, far.y)
        await("endpoint applied after immediate UP", 3_000) {
            var ok = false
            onActivity { a -> ok = selected(a).size == 5 }
            ok
        }
        assertContinuousRange(1, 5)
        settle(220)
        assertContinuousRange(1, 5)
    }

    @Test
    fun contractedRangeRestoresThePreGestureSelection() {
        prepareList()
        // Seed an out-of-range selection with a genuine separate hold/释放.
        val seed = screenPoint(6)
        inject(MotionEvent.ACTION_DOWN, seed.x, seed.y)
        holdForLongPress()
        inject(MotionEvent.ACTION_UP, seed.x, seed.y)
        settle(220)
        assertContinuousRange(6, 6)

        val start = screenPoint(1)
        inject(MotionEvent.ACTION_DOWN, start.x, start.y)
        holdForLongPress()
        val far = screenPoint(4)
        inject(MotionEvent.ACTION_MOVE, far.x, far.y); settle(90)
        assertRangeWithRetained(1, 4, 6)
        val back = screenPoint(2)
        inject(MotionEvent.ACTION_MOVE, back.x, back.y); settle(90)
        assertRangeWithRetained(1, 2, 6)
        inject(MotionEvent.ACTION_UP, back.x, back.y)
        settle(200)
        assertRangeWithRetained(1, 2, 6)
    }

    /** The dragged range plus the selection that existed before the gesture must survive. */
    private fun assertRangeWithRetained(anchor: Int, end: Int, retained: Int) {
        assertEquals("dragged range $anchor..$end plus the pre-existing selection $retained",
            ((anchor..end) + retained).toSet(), selectedIndices().toSet())
    }

    @Test
    fun genuineVerticalDragScrollsAndNeverStartsSelection() {
        prepareList()
        val before = onMain { it.recycler.computeVerticalScrollOffset() }
        val start = screenPoint(2)
        inject(MotionEvent.ACTION_DOWN, start.x, start.y)
        // Move well past slop inside the long-press timeout, then continue like a real finger.
        for (step in 1..8) {
            inject(MotionEvent.ACTION_MOVE, start.x, start.y - slop * 2f * step)
            Thread.sleep(12)
        }
        inject(MotionEvent.ACTION_UP, start.x, start.y - slop * 16f)
        settle(260)
        onActivity { a ->
            assertTrue("ordinary drag did not actually scroll", a.recycler.computeVerticalScrollOffset() > before)
            assertTrue("ordinary scrolling must not select anything", selected(a).isEmpty())
        }
    }

    @Test
    fun gridCrossColumnDragSelectsAContinuousRange() {
        val sources = files(30)
        val pixel = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val pictures = sources.map { source ->
            File(folder, source.nameWithoutExtension + ".png").also { output ->
                output.outputStream().use { pixel.compress(Bitmap.CompressFormat.PNG, 100, it) }
                check(source.delete())
            }
        }
        pixel.recycle()
        launch(30)
        onActivity { a ->
            (field(a, "loading") as? Job)?.cancel()
            setField(a, "page", MainActivity.Page.CATEGORY); setField(a, "category", "图片")
            items(a).apply {
                clear(); add(MainActivity.Item("", kind = "header"))
                add(MainActivity.Item("今天", kind = "date", groupKey = "ui109-date-0", count = 2))
                addAll(pictures.take(2).map { MainActivity.Item(it.name, file = it, kind = "media") })
                add(MainActivity.Item("昨天", kind = "date", groupKey = "ui109-date-1", count = pictures.size - 2))
                addAll(pictures.drop(2).map { MainActivity.Item(it.name, file = it, kind = "media") })
            }
            refresh(a)
        }
        settle()
        onActivity { a ->
            assertTrue("fixture must use the real grid", a.recycler.layoutManager is GridLayoutManager)
            (a.recycler.layoutManager as GridLayoutManager).scrollToPositionWithOffset(2, a.dp(125))
        }
        settle()
        val start = screenPoint(2)
        inject(MotionEvent.ACTION_DOWN, start.x, start.y)
        Thread.sleep(70)
        inject(MotionEvent.ACTION_MOVE, start.x + slop * 1.1f, start.y)
        holdForLongPress()
        onActivity { a -> assertTrue("grid long press did not begin selection", controller(a).isSelecting) }
        val far = screenPoint(6)
        inject(MotionEvent.ACTION_MOVE, far.x, far.y); settle(120)
        inject(MotionEvent.ACTION_UP, far.x, far.y); settle(220)
        onActivity { a ->
            val expected = (2..6).mapNotNull { items(a)[it].file?.path }.toSet()
            assertEquals(expected, selected(a))
            assertFalse("grid selection collapsed", selected(a).isEmpty())
        }
    }
}
