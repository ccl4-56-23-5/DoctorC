package com.wirelessalien.zipxtract

import android.content.Intent
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.nativeui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OpaqueMotionRegressionTest {
    private val ins=InstrumentationRegistry.getInstrumentation();private val context=ins.targetContext
    private lateinit var root:File;private var scenario:ActivityScenario<MainActivity>?=null
    @Before fun setup(){
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")).use{it.readBytes()}
        root=File(context.getExternalFilesDir(null),"motion105-${System.nanoTime()}").apply{mkdirs()}
        repeat(5){File(root,"fixture-$it.txt").writeText("fixture")}
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DIRECTORY).putExtra(MainActivity.EXTRA_DIRECTORY_PATH,root.path))
        android.os.SystemClock.sleep(500);ins.waitForIdleSync()
    }
    @After fun cleanup(){scenario?.close();root.deleteRecursively()}
    private fun textGeometry(v:View):List<List<Any>> = buildList{
        fun visit(view:View){if(view is TextView)add(listOf(view.text.toString(),view.left,view.top,view.width,view.height,view.textSize,view.scaleX,view.scaleY));if(view is ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i))};visit(v)
    }
    @Test fun informationExitKeepsOpaqueTextAndGeometryWithLocalDim(){
        val finished=CountDownLatch(1);val error=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        scenario!!.onActivity{a->
            val d=FileInfoPanel.show(a,File(root,"fixture-0.txt"),"document","7 B"){}
            val surface=d.motionContent
            surface.postDelayed({
                val geometry=textGeometry(surface);val size=surface.width to surface.height
                val dim=d.findViewById<View>(R.id.native_modal_dim);val initial=dim.alpha
                var layouts=0;surface.addOnLayoutChangeListener{_,l,t,r,b,ol,ot,or,ob->if(l!=ol||t!=ot||r!=or||b!=ob)layouts++}
                d.close{finished.countDown()}
                surface.postDelayed({try{
                    assertEquals("glyph layout remains fixed",geometry,textGeometry(surface));assertEquals(size,surface.width to surface.height)
                    assertEquals(1f,surface.alpha);assertEquals(1f,surface.scaleX);assertEquals(1f,surface.scaleY)
                    assertEquals(surface.translationY.toInt().toFloat(),surface.translationY);assertTrue(surface.translationY>0f)
                    assertTrue(dim.alpha<initial);assertEquals(0,layouts)
                    assertEquals(-1,d.window!!.attributes.width);assertEquals(-1,d.window!!.attributes.height)
                    assertEquals(0,d.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }catch(t:Throwable){error.set(t)}},80)
            },360)
        }
        assertTrue(finished.await(4,TimeUnit.SECONDS));ins.waitForIdleSync();error.get()?.let{throw it}
    }
    @Test fun pageTransitionHasOneOpaqueIntegerBoundary(){
        val child=File(root,"next").apply{mkdirs()};repeat(8){File(child,"next-$it.txt").writeText("fixture")}
        val sampled=CountDownLatch(1);val error=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        scenario!!.onActivity{a->
            a.openDirectory(child)
            a.recycler.postDelayed({try{
                val ghost=MainActivity::class.java.getDeclaredField("pageGhost").apply{isAccessible=true}.get(a) as View
                val live=a.recycler;val width=(live.parent as View).width
                assertEquals(1f,ghost.alpha);assertEquals(1f,live.alpha)
                assertEquals(ghost.translationX.toInt().toFloat(),ghost.translationX);assertEquals(live.translationX.toInt().toFloat(),live.translationX)
                assertEquals("shared edge partitions old and new text",ghost.translationX+width,live.translationX)
                assertTrue((live.parent as ViewGroup).clipChildren)
            }catch(t:Throwable){error.set(t)}finally{sampled.countDown()}},100)
        }
        assertTrue(sampled.await(3,TimeUnit.SECONDS));error.get()?.let{throw it}
    }
    @Test fun morphNeverRelayoutsOrTranslatesItsTextBody(){
        scenario!!.onActivity{a->
            val body=TextView(a).apply{text="名称\n日期\n大小";textSize=18f}
            val panel=MorphSurface(a,RectF(800f,50f,920f,180f),RectF(350f,50f,920f,700f),body)
            panel.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2200,View.MeasureSpec.EXACTLY));panel.layout(0,0,1080,2200)
            val geometry=textGeometry(body);val params=body.layoutParams
            for(i in 0..100){panel.updateShape(i/100f);assertSame(params,body.layoutParams);assertEquals(geometry,textGeometry(body));assertEquals(0f,body.translationX);assertEquals(0f,body.translationY);assertEquals(1f,body.scaleX)}
        }
    }
    @Test fun selectionDockExpandsAndContractsThroughIntermediateBounds() {
        val sampled = CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        scenario!!.onActivity { activity ->
            val dock = activity.findViewById<View>(R.id.native_dock)
            val search = activity.findViewById<View>(R.id.native_search_dock)
            val normalWidth = dock.width
            val restingClip = dock.clipToOutline
            val clippedWidths = mutableSetOf<Int>()
            dock.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                if (dock.clipToOutline) clippedWidths += right - left
            }
            val recycler = activity.recycler
            val title = (0 until recycler.childCount)
                .mapNotNull { recycler.getChildAt(it).findViewById<TextView>(R.id.file_title) }
                .first { it.text.startsWith("fixture-") }
            var row: View = title
            while (!row.isLongClickable) row = row.parent as View
            assertTrue(row.performLongClick())
            dock.postDelayed({
                try {
                    val expandedWidth = dock.width
                    assertTrue(expandedWidth > normalWidth)
                    assertEquals(View.GONE, search.visibility)
                    assertEquals(restingClip, dock.clipToOutline)
                    assertTrue("opening needs a clipped frame between the resting widths",
                        clippedWidths.any { it > normalWidth && it < expandedWidth })
                    clippedWidths.clear()
                    activity.onBackPressed()
                    dock.postDelayed({
                        try {
                            assertEquals(normalWidth, dock.width)
                            assertEquals(View.VISIBLE, search.visibility)
                            assertEquals(1f, search.alpha)
                            assertEquals(restingClip, dock.clipToOutline)
                            assertTrue("closing needs clipped intermediate widths",
                                clippedWidths.any { it > normalWidth && it < expandedWidth })
                        } catch (failure: Throwable) {
                            error.set(failure)
                        } finally {
                            sampled.countDown()
                        }
                    }, 380)
                } catch (failure: Throwable) {
                    error.set(failure)
                    sampled.countDown()
                }
            }, 380)
        }
        assertTrue(sampled.await(4, TimeUnit.SECONDS))
        error.get()?.let { throw it }
    }

    @Test fun menuRowsRevealInOrderInsideTheirSingleMovingOutline() {
        scenario!!.onActivity { activity ->
            val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val rows = (1..4).map { i ->
                TextView(activity).apply { text = "操作 $i" }.also {
                    body.addView(it, LinearLayout.LayoutParams(-1, 60))
                }
            }
            val origin = RectF(400f, 250f, 510f, 360f)
            val destination = RectF(140f, 250f, 510f, 560f)
            val surface = MorphSurface(activity, origin, destination, body, rows = rows)
            surface.updateShape(.60f)
            assertTrue(rows.first().alpha > rows.last().alpha)
            assertTrue(rows.last().translationY < rows.first().translationY)
            assertTrue(rows.all { it.scaleX == 1f && it.scaleY == 1f })
            surface.updateShape(1f)
            assertTrue(rows.all { it.alpha == 1f && it.translationY == 0f })
            surface.updateShape(0f)
            assertEquals(origin, surface.currentBounds)
            assertTrue(rows.all { it.alpha == 0f })
        }
    }

    private fun awaitVisible(matcher: org.hamcrest.Matcher<View>) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        val visible = org.hamcrest.Matchers.allOf(matcher, isDisplayed())
        while (true) {
            try {
                onView(visible).check(matches(isDisplayed()))
                return
            } catch (failure: NoMatchingViewException) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw failure
                android.os.SystemClock.sleep(50)
            }
        }
    }

    @Test fun searchOpensAFullPageAndFiltersTheCurrentDirectory() {
        scenario!!.onActivity { assertTrue(it.findViewById<View>(R.id.native_search_dock).performClick()) }
        onView(withText("按类型查找")).check(matches(isDisplayed()))
        onView(withText("按来源查找")).check(matches(isDisplayed()))
        onView(withHint("搜索文件")).check(matches(hasFocus()))
        onView(withHint("搜索文件")).perform(replaceText("fixture-2"), pressImeActionButton())
        onView(withText("当前目录")).check(matches(isDisplayed()))
        awaitVisible(withText("fixture-2.txt"))
        onView(withText("fixture-0.txt")).check(doesNotExist())
        onView(withContentDescription("返回")).perform(click())
        scenario!!.onActivity {
            val dock = it.findViewById<View>(R.id.native_search_dock)
            assertEquals(View.VISIBLE, dock.visibility)
            assertEquals(1f, dock.alpha)
        }
    }

    @Test fun searchSelectionUsesTheBrowserFormsAndCopiesBothFiles() {
        val destination = File(root, "copy-destination").apply { mkdir() }
        scenario!!.onActivity { assertTrue(it.findViewById<View>(R.id.native_search_dock).performClick()) }
        onView(withHint("搜索文件")).perform(replaceText("fixture-"), pressImeActionButton())
        awaitVisible(withText("fixture-1.txt"))
        onView(withText("fixture-1.txt")).perform(longClick())
        onView(withText("fixture-2.txt")).perform(click())
        onView(withText("已选 2 项")).check(matches(isDisplayed()))

        onView(withContentDescription("压缩")).perform(click())
        onView(withId(R.id.archive_name)).check(matches(withText("文件归档")))
        // MotionDialog settles asynchronously after Espresso first sees its fields.
        android.os.SystemClock.sleep(600)
        ins.waitForIdleSync()
        pressBack()
        awaitVisible(withText("已选 2 项"))
        onView(withContentDescription("复制")).perform(click())
        android.os.SystemClock.sleep(600)
        ins.waitForIdleSync()
        onView(withText(destination.name)).perform(click())
        onView(withId(R.id.picker_choose)).perform(click())

        val expected = listOf("fixture-1.txt", "fixture-2.txt")
        val deadline = android.os.SystemClock.elapsedRealtime() + 8000
        while (expected.any { !File(destination, it).isFile } && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(50)
        }
        assertEquals(expected, destination.list()?.sorted())
        for (name in expected) assertEquals(File(root, name).readText(), File(destination, name).readText())
        awaitVisible(withHint("搜索文件"))
        onView(withContentDescription("返回")).perform(click())
        awaitVisible(withId(R.id.native_search_dock))
    }

    @Test fun formExitIgnoresImeRelayoutUntilRemoved(){
        val finished=CountDownLatch(1);val error=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        scenario!!.onActivity{a->
            a.showCreate(listOf(File(root,"fixture-0.txt").path))
            @Suppress("UNCHECKED_CAST")
            val dialogs=com.wirelessalien.zipxtract.activity.FileActionActivity::class.java
                .getDeclaredField("openDialogs").apply{isAccessible=true}.get(a) as Collection<android.app.Dialog>
            val d=dialogs.filterIsInstance<MotionDialog>().last();val outer=d.motionContent
            outer.postDelayed({try{
                val ime=WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,400)).build()
                ViewCompat.dispatchApplyWindowInsets(outer,ime)
                val bottom=outer.paddingBottom;assertTrue(bottom>0)
                d.close{finished.countDown()}
                ViewCompat.dispatchApplyWindowInsets(outer,WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.ime(),Insets.NONE).build())
                assertEquals("closing keeps its measured text layout when IME disappears",bottom,outer.paddingBottom)
                assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,d.window!!.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                assertEquals(1f,outer.alpha);assertEquals(1f,outer.scaleX)
            }catch(t:Throwable){error.set(t);finished.countDown()}},360)
        }
        assertTrue(finished.await(4,TimeUnit.SECONDS));error.get()?.let{throw it}
    }
}
