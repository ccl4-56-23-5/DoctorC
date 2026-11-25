package com.wirelessalien.zipxtract

import android.content.Intent
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
import com.wirelessalien.zipxtract.nativeui.RenamerRules.Kind
import com.wirelessalien.zipxtract.nativeui.RenamerRules.Rule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Ui111RenamerTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val prefs get() = context.getSharedPreferences("batch_rename_rules", 0)
    private lateinit var savedPrefs: Map<String, *>
    private lateinit var folder: File
    private var scenario: ActivityScenario<MainActivity>? = null
    private var panel: MotionDialog? = null
    @Before fun setup() {
        savedPrefs = prefs.all.toMap(); prefs.edit().clear().commit()
        folder = File(context.getExternalFilesDir(null), "rename111-${System.nanoTime()}").apply { mkdirs() }
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
    private fun open() {
        RenamePresetStore(context).remember(RenamePresetStore.LastUsed(emptyList(), RenameFileOrder.Settings()))
        val files = listOf("照片10.txt", "照片2.txt").map { File(folder, it).apply { writeText("fixture-$it") } }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DIRECTORY)
            .putExtra(MainActivity.EXTRA_DIRECTORY_PATH, folder.path))
        scenario!!.onActivity { panel = BatchRenameDialog.show(it, files) }
        await("preview ready") { (tagged("batch_rename_status") as TextView).text.contains("未改变") }
        SystemClock.sleep(350)
    }
    private fun seed(name: String, text: String, key: RenameFileOrder.Key = RenameFileOrder.Key.ORIGINAL) {
        RenamePresetStore(context).save(name, listOf(Rule(Kind.INSERT, text = text, name = "前缀规则")), RenameFileOrder.Settings(key))
    }
    @Test fun imeSpacesSurviveSaveEditAndPreview() {
        open(); click("batch_rename_add")
        main {
            val field = tagged("batch_rename_rule_text") as EditText
            field.requestFocus()
            val input = field.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
            assertTrue(input.commitText(" A  B ", 1))
            assertEquals(" A  B ", field.text.toString())
        }
        main { (tagged("batch_rename_rule_text") as EditText).onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.commitText(" ", 1) }
        assertEquals(" A  B  ", main { (tagged("batch_rename_rule_text") as EditText).text.toString() })
        click("batch_rename_rule_save")
        await("spaces retained in output") {
            (tagged("batch_rename_after_0") as TextView).text.toString() == "新：照片10 A  B  .txt"
        }
        click("batch_rename_rename_rule")
        main { (tagged("batch_rename_rule_name") as EditText).setText("添加分隔空格") }
        click("batch_rename_rule_save")
        await("existing rule renamed without changing spaces") {
            all().filterIsInstance<TextView>().any { it.text.contains("添加分隔空格") } &&
                (tagged("batch_rename_after_0") as TextView).text.toString() == "新：照片10 A  B  .txt"
        }
        click("batch_rename_execute")
        await("space-containing names executed without changing bytes") {
            File(folder, "照片10 A  B  .txt").isFile && File(folder, "照片2 A  B  .txt").isFile
        }
        assertEquals("fixture-照片10.txt", File(folder, "照片10 A  B  .txt").readText())
        assertEquals("fixture-照片2.txt", File(folder, "照片2 A  B  .txt").readText())
    }
    @Test fun spaceOnlyRuleIsVisibleAndSavedAsLiteralAsciiSpaces() {
        open(); click("batch_rename_add"); main { (tagged("batch_rename_rule_text") as EditText).onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.commitText(" ", 1) }; main { (tagged("batch_rename_rule_text") as EditText).onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.commitText(" ", 1) }
        assertEquals("  ", main { (tagged("batch_rename_rule_text") as EditText).text.toString() })
        click("batch_rename_rule_save")
        await("space-only rule described") {
            (tagged("batch_rename_rule_description_0") as TextView).text.contains("2 个空格") &&
                (tagged("batch_rename_after_0") as TextView).text.toString() == "新：照片10  .txt"
        }
    }
    @Test fun renameSavedSetKeepsIdentityRulesAndSortAndRejectsNameCollision() {
        val store = RenamePresetStore(context)
        val original = store.save("旧名称", listOf(Rule(Kind.INSERT, text = "  ", mode = "suffix", name = "空格")),
            RenameFileOrder.Settings(RenameFileOrder.Key.MODIFIED, true))
        seed("另一套", "other")
        open(); click("batch_rename_load_preset"); menu("管理已存方案…"); menu("旧名称"); menu("改名 · 旧名称")
        main { (tagged("batch_rename_preset_title") as EditText).setText("照片整理") }
        click("batch_rename_preset_commit")
        await("renamed store") { store.list().any { it.name == "照片整理" } }
        val renamed = store.list().first { it.id == original.id }
        assertEquals(original.copy(name = "照片整理"), renamed)
        val before = prefs.getString("named_presets_v2", null)
        assertTrue(runCatching { store.rename(original.id, "另一套") }.isFailure)
        assertEquals(before, prefs.getString("named_presets_v2", null))
        click("batch_rename_load_preset"); menu("照片整理 · 1 条")
        await("renamed set loads exact spaces") {
            (tagged("batch_rename_after_0") as TextView).text.toString().endsWith("  .txt")
        }
    }
}
