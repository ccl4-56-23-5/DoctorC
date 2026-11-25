package com.wirelessalien.zipxtract

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.nativeui.ClockRenameDrawable
import com.wirelessalien.zipxtract.nativeui.ClockRenameIconView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 1.0.9 rename icon: one dark graphite silhouette shared with the neighbouring dock icons, plus a
 * small white glint that points along the CURRENT local hour hand (minutes included).
 * The user confirmed 12 up, 3 right, 6 down, 9 left.
 */
@RunWith(AndroidJUnit4::class)
class Ui109RenameIconTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun cleanup() {
        scenario?.let { runCatching { it.close() } }
        scenario = null
    }

    /** Runs on the main thread while ActivityScenario keeps its own lifecycle bookkeeping. */
    private fun <T> onMain(block: (MainActivity) -> T): T {
        var value: T? = null
        var failure: Throwable? = null
        ins.runOnMainSync {
            try { checkNotNull(scenario).onActivity { value = block(it) } } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Test fun hourHandAngleIncludesTheMinuteFraction() {
        assertEquals(0f, ClockRenameDrawable.hourHandAngleDegrees(0, 0), .001f)
        assertEquals(90f, ClockRenameDrawable.hourHandAngleDegrees(3, 0), .001f)
        assertEquals(180f, ClockRenameDrawable.hourHandAngleDegrees(6, 0), .001f)
        assertEquals(270f, ClockRenameDrawable.hourHandAngleDegrees(9, 0), .001f)
        assertEquals(45f, ClockRenameDrawable.hourHandAngleDegrees(1, 30), .001f)
        assertEquals(315f, ClockRenameDrawable.hourHandAngleDegrees(22, 30), .001f)
        assertEquals(90f, ClockRenameDrawable.hourHandAngleDegrees(12, 0) + 90f, .001f) // 12:00 is 0°, i.e. straight up
        assertEquals(0f, ClockRenameDrawable.hourHandAngleDegrees(12, 0), .001f)
        // 11:59 is one minute short of twelve, not a jump or a minute-hand reading.
        assertEquals(359.5f, ClockRenameDrawable.hourHandAngleDegrees(11, 59), .001f)
    }

    /** Renders a frame whose white glint must sit on the hour-hand side of the silhouette. */
    private fun render(hour: Int, minute: Int, dark: Boolean = false, alpha: Int = 255): Bitmap {
        val calendar = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 4, hour, minute, 0); set(Calendar.MILLISECOND, 0)
        }
        val fixed = calendar.timeInMillis
        val drawable = ClockRenameDrawable(dark, ClockRenameDrawable.TimeSource { fixed })
        drawable.setBounds(0, 0, 192, 192)
        drawable.alpha = alpha
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * The glint is a soft highlight: over the dark body it composites to roughly 229, so a strict
     * "pure white" threshold misses it entirely. Use the brightest cluster instead and exclude the
     * fixed rename cursor region, the only other bright structure, so the centroid cannot collapse
     * back to the icon centre.
     */
    private fun glintDirection(bitmap: Bitmap): Pair<Double, Double> {
        var brightest = 0
        val samples = ArrayList<Triple<Int, Int, Int>>()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (x in 78..114 && y in 70..134) continue // fixed rename cursor strokes (with their cap radius)
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) < 24) continue
            val luma = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            if (luma > brightest) brightest = luma
            samples += Triple(x, y, luma)
        }
        assertTrue("the icon must contain a bright accent", brightest > 150)
        // The glint is a soft radial gradient, so take the brightest cluster rather than an
        // absolute cutoff: over the dark body its core is ~229 and only a few dozen pixels reach it.
        val top = samples.sortedByDescending { it.third }.take(40)
        var sumX = 0.0; var sumY = 0.0
        for ((x, y, _) in top) { sumX += x; sumY += y }
        assertTrue("bright accent must cover a few pixels", top.size >= 4)
        return (sumX / top.size - 96.0) to (sumY / top.size - 96.0)
    }

    private fun glintStats(bitmap: Bitmap): String {
        val (dx, dy) = runCatching { glintDirection(bitmap) }.getOrElse { return "unavailable" }
        return "offset=(${"%.1f".format(dx)},${"%.1f".format(dy)})"
    }

    private fun assertDirection(hour: Int, minute: Int, expectedX: Double, expectedY: Double) {
        val bitmap = render(hour, minute)
        try {
            Log.i("Ui109Icon", "render ${hour}:${minute} centre=" + glintStats(bitmap))
            runCatching {
                val output = java.io.File(context.getExternalFilesDir("qa109"), "icon-$hour-$minute.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val (dx, dy) = glintDirection(bitmap)
            val length = hypot(dx, dy)
            assertTrue("glint offset $length too small (stats=${glintStats(bitmap)})", length > 8.0)
            val dot = (dx / length) * expectedX + (dy / length) * expectedY
            val degrees = Math.toDegrees(kotlin.math.acos(dot.coerceIn(-1.0, 1.0)))
            assertTrue("glint points ${"%.1f".format(degrees)}° away from the hour hand at ${"%02d".format(hour)}:${"%02d".format(minute)}",
                degrees < 32.0)
        } finally { bitmap.recycle() }
    }

    @Test fun glintFollowsTheLocalHourHandAtTheFourCardinalHours() {
        assertDirection(12, 0, 0.0, -1.0)
        assertDirection(3, 0, 1.0, 0.0)
        assertDirection(6, 0, 0.0, 1.0)
        assertDirection(9, 0, -1.0, 0.0)
    }

    @Test fun minutesRotateTheGlintSoItIsNotAHourOnlySnapshot() {
        // 1:30 is 45° clockwise from twelve: upper-right, between up and right.
        assertDirection(1, 30, sin(Math.toRadians(45.0)), -cos(Math.toRadians(45.0)))
        // 10:30 is 315°: upper-left.
        assertDirection(10, 30, sin(Math.toRadians(315.0)), -cos(Math.toRadians(315.0)))
    }

    @Test fun bodyIsANeutralSilhouetteNotTheOldColourTile() {
        val bitmap = render(3, 0)
        try {
            var darkest = Int.MAX_VALUE; var darkestPixel = 0
            var coloured = 0
            var opaque = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                if (alpha < 200) continue
                opaque++
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                if (maxOf(r, g, b) - minOf(r, g, b) > 24 && maxOf(r, g, b) < 240) coloured++
                val luma = r + g + b
                if (luma < darkest) { darkest = luma; darkestPixel = pixel }
            }
            assertTrue("icon body should cover a useful area", opaque > 2000)
            assertEquals("no coloured app-tile pixels (old blue/purple/yellow artwork)", 0, coloured)
            assertTrue("body must be dark graphite", Color.red(darkestPixel) < 90 && Color.green(darkestPixel) < 90)
        } finally { bitmap.recycle() }
    }

    @Test fun zeroAlphaDrawsNothing() {
        val bitmap = render(3, 0, alpha = 0)
        try {
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                assertEquals("alpha 0 must be fully transparent", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun darkThemeKeepsTheGlintWhite() {
        val bitmap = render(3, 0, dark = true)
        try {
            val (dx, _) = glintDirection(bitmap)
            assertTrue("dark theme glint must stay on the hour-hand side", dx > 8.0)
        } finally { bitmap.recycle() }
    }

    @Test fun minuteTickRunsOnlyWhileVisibleAndResumed() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        var view: ClockRenameIconView? = null
        var container: FrameLayout? = null
        try {
            onMain { activity ->
                val box = FrameLayout(activity)
                val icon = ClockRenameIconView(activity).apply { setHostResumed(true) }
                box.addView(icon, FrameLayout.LayoutParams(96, 96))
                (activity.window.decorView as ViewGroup).addView(box)
                container = box
                view = box.getChildAt(0) as ClockRenameIconView
            }
            SystemClock.sleep(120); ins.runOnMainSync { }
            ins.runOnMainSync { assertTrue("no minute tick while attached", view!!.isMinuteTickScheduled) }

            ins.runOnMainSync { view!!.setHostResumed(false) }
            ins.runOnMainSync {
                assertTrue("pause must cancel the tick", !view!!.isMinuteTickScheduled)
                assertTrue("pause must unregister the clock receiver", !view.isClockReceiverRegistered)
            }
            ins.runOnMainSync { view!!.setHostResumed(true) }
            ins.runOnMainSync { assertTrue(view!!.isMinuteTickScheduled) }

            ins.runOnMainSync { container!!.removeView(view) }
            ins.runOnMainSync {
                assertTrue("detach must cancel the tick", !view!!.isMinuteTickScheduled)
                assertTrue("detach must unregister the clock receiver", !view.isClockReceiverRegistered)
            }
        } finally {
            ins.runOnMainSync { runCatching { container?.let { (it.parent as? ViewGroup)?.removeView(it) } } }
            scenario?.close(); scenario = null
        }
    }

    @Test fun dockUsesTheClockIconForRename() {
        val folder = java.io.File(context.getExternalFilesDir(null), "ui109-icon-${System.nanoTime()}")
        check(folder.mkdirs())
        try {
            java.io.File(folder, "one.txt").writeText("payload")
            scenario = ActivityScenario.launch(android.content.Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DIRECTORY)
                .putExtra(MainActivity.EXTRA_DIRECTORY_PATH, folder.path))
            SystemClock.sleep(600); ins.runOnMainSync { }
            onMain { activity ->
                // Long-press semantics are covered by Ui109DragSelectionTest; enter selection mode
                // the same way the host does so the dock rebuilds.
                val selected = MainActivity::class.java.getDeclaredField("selected")
                    .apply { isAccessible = true }.get(activity) as MutableSet<*>
                @Suppress("UNCHECKED_CAST")
                (selected as MutableSet<String>).add(java.io.File(folder, "one.txt").path)
                MainActivity::class.java.getDeclaredMethod("refreshViews", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(activity, false)
            }
            SystemClock.sleep(260); ins.runOnMainSync { }
            onMain { activity ->
                val field = MainActivity::class.java.getDeclaredField("renameIconView").apply { isAccessible = true }
                val icon = field.get(activity) as ClockRenameIconView?
                assertTrue("the rename dock slot must host the clock icon", icon != null)
                assertTrue("dock rename icon must tick while the screen is resumed", icon!!.isMinuteTickScheduled)
            }
        } finally {
            scenario?.let { runCatching { it.close() } }
            scenario = null
            folder.deleteRecursively()
        }
    }
}
