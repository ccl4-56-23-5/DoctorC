package com.wirelessalien.zipxtract

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.exifinterface.media.ExifInterface
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.nativeui.*
import com.wirelessalien.zipxtract.nativeui.RenamerRules.DateSource
import com.wirelessalien.zipxtract.nativeui.RenamerRules.Kind
import com.wirelessalien.zipxtract.nativeui.RenamerRules.Rule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class Ui112RenamerTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val prefs get() = context.getSharedPreferences("batch_rename_rules", 0)
    private lateinit var savedPrefs: Map<String, *>
    private lateinit var folder: File
    private var scenario: ActivityScenario<MainActivity>? = null
    private var panel: MotionDialog? = null
    @Before fun setup() {
        savedPrefs = prefs.all.toMap(); prefs.edit().clear().commit()
        folder = File(context.getExternalFilesDir(null), "rename112-${System.nanoTime()}").apply { mkdirs() }
    }
    @After fun cleanup() {
        main { panel?.dispose() }; scenario?.close(); folder.deleteRecursively()
        prefs.edit().clear().apply {
            savedPrefs.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value); is Long -> putLong(key, value); is Float -> putFloat(key, value)
            } }
        }.commit()
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        ins.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun all(): List<View> = WindowInspector.getGlobalWindowViews().flatMap { root ->
        buildList { fun visit(v: View) { add(v); if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i)) }; visit(root) }
    }
    private fun tagged(tag: String) = all().first { it.tag == tag && it.isAttachedToWindow }
    private fun await(label: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 7000
        while (SystemClock.uptimeMillis() < end) {
            if (main { runCatching(check).getOrDefault(false) }) return
            SystemClock.sleep(60)
        }
        fail("Timeout: $label")
    }
    private fun click(tag: String) { main { assertTrue(tagged(tag).performClick()) }; SystemClock.sleep(420) }
    private fun menu(text: String) {
        await("menu $text") { all().filterIsInstance<TextView>().any { it.text.toString() == text } }
        main {
            val label = all().filterIsInstance<TextView>().last { it.text.toString() == text }
            assertTrue((label.parent as View).performClick())
        }
        SystemClock.sleep(420)
    }
    private fun open(files: List<File> = listOf("照片10.txt", "照片2.txt").map {
        File(folder, it).apply { writeText("fixture-$it") }
    }, previewReady: String = "未改变") {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DIRECTORY)
            .putExtra(MainActivity.EXTRA_DIRECTORY_PATH, folder.path))
        scenario!!.onActivity { panel = BatchRenameDialog.show(it, files) }
        await("preview ready") { (tagged("batch_rename_status") as TextView).text.contains(previewReady) }
        SystemClock.sleep(350)
    }
    private fun seed(name: String, text: String, key: RenameFileOrder.Key = RenameFileOrder.Key.ORIGINAL) {
        RenamePresetStore(context).save(name, listOf(Rule(Kind.INSERT, text = text, name = "前缀规则")), RenameFileOrder.Settings(key))
    }

    private fun photo(name: String, date: String, modified: Long, offset: String = "+00:00"): File {
        val file = File(folder, name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            saveAttributes()
        }
        assertTrue(file.setLastModified(modified))
        return file
    }

    @Test fun dateSourceControlsPreviewAndPersistsInSavedRules() {
        val image = photo("photo.jpg", "2015:03:02 00:15:00", 1_800_000_000_000L, "+08:00")
        val document = File(folder, "note.txt").apply {
            writeText("date-source fixture")
            assertTrue(setLastModified(1_500_000_000_000L))
        }
        val pattern = "yyyy-MM-dd"
        val format = SimpleDateFormat(pattern, Locale.ROOT)
        val modified = format.format(Date(document.lastModified()))
        val imageModified = format.format(Date(image.lastModified()))
        open(listOf(image, document))
        click("batch_rename_add"); click("batch_rename_rule_kind"); menu("日期")
        assertTrue(main { (tagged("batch_rename_rule_date_source") as TextView).text.contains(DateSource.MODIFIED.title) })
        click("batch_rename_rule_mode"); menu("作为前缀")
        main {
            (tagged("batch_rename_rule_date_format") as EditText).setText(pattern)
            (tagged("batch_rename_rule_replacement") as EditText).setText("_")
        }
        click("batch_rename_rule_save")
        await("default uses photo mtime") {
            (tagged("batch_rename_after_0") as TextView).text.toString() == "新：${imageModified}_photo.jpg"
        }
        click("batch_rename_edit"); click("batch_rename_rule_date_source"); menu(DateSource.EXIF.title)
        click("batch_rename_rule_save")
        await("photo keeps the EXIF wall date and document blocks strict mode") {
            (tagged("batch_rename_after_0") as TextView).text.toString() == "新：2015-03-02_photo.jpg" &&
                all().filterIsInstance<TextView>().any { it.text.contains("没有可读取的 EXIF 拍摄时间") } &&
                !tagged("batch_rename_execute").isEnabled
        }
        click("batch_rename_edit")
        assertTrue(main { (tagged("batch_rename_rule_date_source") as TextView).text.contains(DateSource.EXIF.title) })
        click("batch_rename_rule_date_source"); menu(DateSource.EXIF_OR_MODIFIED.title)
        click("batch_rename_rule_save")
        await("mixed preview keeps EXIF and uses document mtime") {
            (tagged("batch_rename_after_0") as TextView).text.toString() == "新：2015-03-02_photo.jpg" &&
                (tagged("batch_rename_after_1") as TextView).text.toString() == "新：${modified}_note.txt" &&
                tagged("batch_rename_execute").isEnabled
        }
        val store = RenamePresetStore(context)
        val saved = store.lastUsed()?.rules?.single() ?: error("Missing last rule")
        assertEquals(DateSource.EXIF_OR_MODIFIED, saved.dateSource)
        assertEquals(saved, RenamePresetStore.decodeRules(RenamePresetStore.encodeRules(listOf(saved))).single())
        assertEquals(DateSource.MODIFIED, RenamePresetStore.decodeRules(JSONArray("""[{"kind":"DATE"}]""")).single().dateSource)
        click("batch_rename_execute")
        await("renamed to both previewed dates") {
            File(folder, "2015-03-02_photo.jpg").isFile && File(folder, "${modified}_note.txt").isFile
        }
    }

    @Test fun defaultCaptureOrderUsesPhotoAndVideoMetadataBeforeNumbering() {
        val old = photo("最旧.jpg", "2015:03:02 13:00:00", 1_800_000_000_000L)
        val newest = photo("最新.jpg", "2020:05:06 00:00:00", 1_400_000_000_000L)
        val video = File(folder, "视频.mp4").apply {
            ins.context.assets.open("capture-order-2017.mp4").use { input ->
                outputStream().use { output -> input.copyTo(output) }
            }
            assertTrue(setLastModified(1_900_000_000_000L))
        }
        val plain = File(folder, "无元数据.txt").apply {
            writeText("fallback")
            assertTrue(setLastModified(1_560_000_000_000L))
        }
        // An old draft used selection order by default. Upgrade it without losing the rule.
        val rule = Rule(Kind.SERIALIZE, mode = "suffix", width = 1, text = " ")
        val raw = org.json.JSONObject().apply {
            put("sort", "ORIGINAL")
            put("rules", RenamePresetStore.encodeRules(listOf(rule)))
        }
        prefs.edit().putString("last_used_v3", raw.toString()).commit()
        open(listOf(newest, plain, video, old), "4 项将改名")
        val expected = listOf("最旧 1.jpg", "视频 2.mp4", "无元数据 3.txt", "最新 4.jpg")
        await("capture time, oldest first, then numbered") {
            expected.withIndex().all { (i, name) ->
                (tagged("batch_rename_after_$i") as TextView).text.toString() == "新：$name"
            }
        }
        main {
            assertEquals(1_491_125_400_000L, MediaFacts.captureTime(video))
            val preview = (tagged("batch_rename_after_0") as TextView).text as Spanned
            val marker = preview.getSpans(0, preview.length, ReplacementSpan::class.java).single()
            assertEquals(" ", preview.subSequence(preview.getSpanStart(marker), preview.getSpanEnd(marker)).toString())
        }
    }

    @Test fun sortingMenuUsesTheButtonBoundsAndRestoresTheButtonOnClose() {
        open()
        val anchor = main { tagged("batch_rename_sort_files") }
        main { assertTrue(anchor.performClick()) }
        await("one open surface contains the button origin") {
            val surface = all().filterIsInstance<MorphSurface>().single()
            surface.progress > .99f && anchor.alpha == 0f &&
                android.graphics.RectF.intersects(surface.currentBounds, surface.originBounds)
        }
        menu("拍摄时间")
        await("stationary button restored") { anchor.alpha == 1f && all().none { it is MorphSurface } }
    }

    @Test fun separatorBeforeNumberStaysVisibleAtARealWrapAndRenamesWithOrdinarySpace() {
        val stem = "2026-11-01 19：41 徕卡·一瞬初试"
        val file = File(folder, "$stem.jpg").apply { writeText("fixture") }
        val insert = Rule(Kind.INSERT, text = " ", mode = "suffix")
        val number = Rule(Kind.SERIALIZE, mode = "suffix", start = 1, step = 1, width = 2)
        RenamePresetStore(context).remember(RenamePresetStore.LastUsed(
            listOf(insert, number), RenameFileOrder.Settings(RenameFileOrder.Key.ORIGINAL)))
        open(listOf(file), "1 项将改名")
        val expected = "新：$stem 01.jpg"
        main {
            val view = tagged("batch_rename_after_0") as TextView
            assertEquals(expected, view.text.toString())
            val separator = expected.lastIndexOf(' ')
            val dot = expected.indexOf('·')
            val raw = view.text as Spanned
            val markers = raw.getSpans(0, raw.length, ReplacementSpan::class.java)
            assertEquals(expected.count { it == ' ' }, markers.size)
            assertTrue(raw.getSpans(dot, dot + 1, ReplacementSpan::class.java).isEmpty())
            assertEquals('·', view.layout.text[dot])
            assertEquals(1, raw.getSpans(separator, separator + 1, ReplacementSpan::class.java).size)
            val targetWidth = android.text.Layout.getDesiredWidth(view.layout.text, 0, separator + 1, view.paint).toInt()
            val wrapsAtSeparator = (targetWidth - 5..targetWidth + 20).any { width ->
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
                val layout = view.layout
                val line = layout.getLineForOffset(separator)
                line < layout.lineCount - 1 && layout.getLineEnd(line) == separator + 1 &&
                    layout.getLineVisibleEnd(line) == separator + 1 && layout.text[separator] == '\u00A0'
            }
            assertTrue("the marker must survive at the last drawn position on a wrapped line", wrapsAtSeparator)
            assertTrue(all().filterIsInstance<TextView>().any {
                it.text.toString().startsWith("编号：起始 1-步长 1-位数 2")
            })
        }
        click("batch_rename_execute")
        await("actual file has an ordinary space before its number") { File(folder, "$stem 01.jpg").isFile }
        assertEquals("fixture", File(folder, "$stem 01.jpg").readText())
    }

    @Test fun directSingleAndTrailingSpacesAreVisibleAndSurviveRealRename() {
        open(); click("batch_rename_add")
        val location=IntArray(2)
        main {
            val field=tagged("batch_rename_rule_text") as LiteralRuleEditText
            assertTrue("literal input must accept focus in touch mode",field.isFocusableInTouchMode)
            field.getLocationOnScreen(location);location[0]+=field.width/2;location[1]+=field.height/2
        }
        val down=SystemClock.uptimeMillis()
        ins.sendPointerSync(android.view.MotionEvent.obtain(down,down,android.view.MotionEvent.ACTION_DOWN,location[0].toFloat(),location[1].toFloat(),0))
        ins.sendPointerSync(android.view.MotionEvent.obtain(down,down+40,android.view.MotionEvent.ACTION_UP,location[0].toFloat(),location[1].toFloat(),0))
        await("touch-focused literal field") { tagged("batch_rename_rule_text").hasFocus() }
        main {
            assertFalse(all().any { it.tag == "batch_rename_rule_space_text" })
            val field = tagged("batch_rename_rule_text") as LiteralRuleEditText
            val input = field.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
            assertTrue(input.setComposingText(" ", 1)); assertTrue(input.finishComposingText())
            assertEquals(" ", field.text.toString())
            val bitmap = android.graphics.Bitmap.createBitmap(field.width, field.height, android.graphics.Bitmap.Config.ARGB_8888)
            field.draw(android.graphics.Canvas(bitmap))
            val x = (field.compoundPaddingLeft + field.paint.measureText(" ") / 2).toInt()
            val bottom = (field.totalPaddingTop + field.layout.getLineBaseline(0) + field.paint.textSize * .06f).toInt()
            val guideVisible = (bottom - 1..bottom + 1).any { y ->
                android.graphics.Color.luminance(bitmap.getPixel(x, y)) < .8f
            }
            val middle = (field.totalPaddingTop + field.layout.getLineBaseline(0) + field.paint.ascent() * .33f).toInt()
            val middleClear = android.graphics.Color.luminance(bitmap.getPixel(x, middle)) >= .8f
            bitmap.recycle()
            assertTrue("a lone space draws a low guide without a middle dot", guideVisible && middleClear)
            field.selectAll(); input.commitText("报告 ", 1); input.finishComposingText()
            assertEquals("报告 ", field.text.toString())
        }
        click("batch_rename_rule_save")
        await("trailing space kept in preview") { (tagged("batch_rename_after_0") as TextView).text.toString() == "新：照片10报告 .txt" }
        click("batch_rename_edit")
        assertEquals("报告 ", main { (tagged("batch_rename_rule_text") as EditText).text.toString() })
        click("batch_rename_rule_cancel"); click("batch_rename_execute")
        await("renamed literal spaces") { File(folder, "照片10报告 .txt").isFile }
        assertEquals("fixture-照片10.txt", File(folder, "照片10报告 .txt").readText())
    }

    @Test fun reopeningRestoresUnsavedChainAndSortingIncludingAnEmptyChain() {
        open(); click("batch_rename_add")
        main { (tagged("batch_rename_rule_text") as EditText).setText(" ") }
        click("batch_rename_rule_save"); click("batch_rename_sort_files"); menu("名称（自然）"); click("batch_rename_sort_direction")
        val expected = RenamePresetStore(context).lastUsed()!!
        assertEquals(" ", expected.rules.single().text); assertTrue(expected.order.descending)
        main { panel!!.close {} }; await("panel closed") { !panel!!.isShowing }
        scenario!!.onActivity { panel = BatchRenameDialog.show(it, folder.listFiles()!!.toList()) }
        await("last chain restored") { all().any { it.tag == "batch_rename_rule_0" } && (tagged("batch_rename_after_0") as TextView).text.toString().endsWith(" .txt") }
        assertEquals(expected, RenamePresetStore(context).lastUsed())
        click("batch_rename_remove")
        assertTrue(RenamePresetStore(context).lastUsed()!!.rules.isEmpty())
        main { panel!!.close {} }; await("second close") { !panel!!.isShowing }
        scenario!!.onActivity { panel = BatchRenameDialog.show(it, folder.listFiles()!!.toList()) }
        await("explicit empty restored") { (tagged("batch_rename_status") as TextView).text.contains("未改变") }
        assertFalse(main { all().any { it.tag == "batch_rename_rule_0" } })
    }

    @Test fun freshNumberingHasNoImplicitSpaceAndExplicitSpacesRemainUserControlled() {
        open(); click("batch_rename_add"); menuKind()
        assertEquals("", main { (tagged("batch_rename_rule_text") as EditText).text.toString() })
        assertEquals("", main { (tagged("batch_rename_rule_replacement") as EditText).text.toString() })
        click("batch_rename_rule_save")
        await("suffix without separator") { (tagged("batch_rename_after_0") as TextView).text.toString() == "新：照片10001.txt" }
        click("batch_rename_edit"); click("batch_rename_rule_mode"); menu("作为前缀")
        click("batch_rename_rule_save")
        await("prefix without separator") { (tagged("batch_rename_after_0") as TextView).text.toString() == "新：001照片10.txt" }
        click("batch_rename_edit")
        main { (tagged("batch_rename_rule_replacement") as EditText).setText(" ") }
        click("batch_rename_rule_save")
        await("only explicit separator appears") { (tagged("batch_rename_after_0") as TextView).text.toString() == "新：001 照片10.txt" }
        click("batch_rename_edit")
        main { (tagged("batch_rename_rule_replacement") as EditText).setText("") }
        click("batch_rename_rule_save")
        await("separator removed") { (tagged("batch_rename_after_0") as TextView).text.toString() == "新：001照片10.txt" }
        click("batch_rename_execute")
        await("numbered real files without a leading space") { File(folder,"001照片10.txt").isFile && File(folder,"002照片2.txt").isFile }
        assertEquals("fixture-照片10.txt",File(folder,"001照片10.txt").readText())
    }
    private fun menuKind() { click("batch_rename_rule_kind"); menu("编号") }

    @Test fun storedLastDraftSurvivesANewStoreAndPresetRenameWithoutChangingRules() {
        val store=RenamePresetStore(context)
        val preset=store.save("原方案",listOf(Rule(Kind.SERIALIZE,mode="suffix")),RenameFileOrder.Settings())
        val last=RenamePresetStore.LastUsed(listOf(Rule(Kind.INSERT,text=" ",mode="suffix",enabled=false,name="空格")),
            RenameFileOrder.Settings(RenameFileOrder.Key.SIZE,true),preset.id,preset.name)
        store.remember(last);store.rename(preset.id,"新方案")
        val restored=RenamePresetStore(context).lastUsed()!!
        assertEquals(last.copy(name="新方案"),restored)
        prefs.edit().putString("last_used_v3","broken{").commit()
        assertTrue(runCatching { store.lastUsed() }.isFailure)
        assertEquals("broken{",prefs.getString("last_used_v3",null))
        assertEquals("新方案",store.list().single().name)
    }

    @Test fun shortTapOnRuleSelectsReplacementText() {
        val file = File(folder, "old-title.txt").apply { writeText("fixture") }
        val store = RenamePresetStore(context)
        store.remember(RenamePresetStore.LastUsed(
            listOf(Rule(Kind.REPLACE, mode = "all", text = "old", replacement = "new")),
            RenameFileOrder.Settings()
        ))
        open(listOf(file), "1 项将改名")
        click("batch_rename_rule_0")
        await("replacement focused") {
            all().filterIsInstance<EditText>().any {
                it.tag == "batch_rename_rule_replacement" && it.hasFocus()
            }
        }
        main {
            val field = tagged("batch_rename_rule_replacement") as EditText
            assertEquals(0, field.selectionStart)
            assertEquals(field.length(), field.selectionEnd)
            field.setText("final")
        }
        click("batch_rename_rule_save")
        await("replacement preview updates") {
            (tagged("batch_rename_after_0") as TextView).text.toString() == "新：final-title.txt"
        }
    }

    @Test fun mainPanelClosesBackToItsActualSourceEvenWhenInterrupted() {
        val file=File(folder,"a.txt").apply { writeText("a") }
        scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var origin: View
        val bounds=android.graphics.Rect()
        scenario!!.onActivity { activity ->
            val root=activity.window.decorView.findViewById<android.widget.FrameLayout>(android.R.id.content)
            origin=TextView(activity).apply { text="重命名" }
            root.addView(origin,android.widget.FrameLayout.LayoutParams(150,100).apply { leftMargin=333;topMargin=555 })
        }
        ins.waitForIdleSync()
        val position=IntArray(2)
        main { origin.getLocationOnScreen(position);bounds.set(position[0],position[1],position[0]+origin.width,position[1]+origin.height) }
        scenario!!.onActivity { panel=BatchRenameDialog.show(it,listOf(file),origin) }
        SystemClock.sleep(65)
        main { panel!!.close {} };await("reversed morph finished") { !panel!!.isShowing }
        main {
            val view=panel!!.motionContent;val outline=android.graphics.Outline();view.outlineProvider.getOutline(view,outline)
            val clip=android.graphics.Rect();assertTrue(outline.getRect(clip))
            val parent=view.parent as View;val location=IntArray(2);parent.getLocationOnScreen(location)
            clip.offset((location[0]+view.left+view.translationX).toInt(),(location[1]+view.top+view.translationY).toInt())
            assertEquals(bounds,clip);assertEquals(View.VISIBLE,origin.visibility);assertEquals(1f,origin.alpha)
            assertEquals(1f,view.scaleX);assertEquals(1f,view.scaleY)
        }
    }
}
