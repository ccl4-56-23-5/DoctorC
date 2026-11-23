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
class Ui110RenamerTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val prefs get() = context.getSharedPreferences("batch_rename_rules", 0)
    private lateinit var savedPrefs: Map<String, *>
    private lateinit var folder: File
    private var scenario: ActivityScenario<MainActivity>? = null
    private var panel: MotionDialog? = null
    @Before fun setup() {
        savedPrefs = prefs.all.toMap(); prefs.edit().clear().commit()
        folder = File(context.getExternalFilesDir(null), "rename110-${System.nanoTime()}").apply { mkdirs() }
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
    @Test fun multipleNamesAndEveryRuleParameterRoundTripWithoutOverwritingOtherSets() {
        val store = RenamePresetStore(context)
        val rules = listOf(Rule(Kind.REGEX, enabled = false, skipExtension = false, text = "(.*)", replacement = "\$1_😀",
            mode = "all", position = 3, count = 4, ignoreCase = true, start = 7, step = 2, width = 9,
            dateFormat = "yyyy_MM", name = "清理照片"), Rule(Kind.SERIALIZE, mode = "suffix"))
        store.save("聚餐照片", rules, RenameFileOrder.Settings(RenameFileOrder.Key.MODIFIED, true))
        seed("文档", "doc_")
        val first = RenamePresetStore(context).list()
        assertEquals(listOf("聚餐照片", "文档"), first.map { it.name })
        assertEquals(rules, first[0].rules); assertTrue(first[0].order.descending)
        store.save("聚餐照片", rules.reversed(), RenameFileOrder.Settings())
        val next = store.list()
        assertEquals(2, next.size); assertEquals(first[0].id, next[0].id); assertEquals(first[1], next[1])
        assertEquals(rules.reversed(), next[0].rules)
    }
    @Test fun legacyPresetSurvivesMigrationAndDeletionOfAnotherSet() {
        val rule = Rule(Kind.INSERT, text = "legacy_")
        val json = RenamePresetStore.encodeRules(listOf(rule)).toString()
        prefs.edit().putString("last_preset_v1", json).commit()
        val store = RenamePresetStore(context)
        assertEquals("旧版规则", store.list().single().name)
        val added = store.save("新方案", listOf(rule.copy(text = "new_")), RenameFileOrder.Settings())
        store.delete(added.id)
        assertEquals(listOf(rule), store.list().single().rules)
        assertEquals(json, prefs.getString("last_preset_v1", null))
    }
    @Test fun malformedStorageFailsWithoutDiscardingExistingBytes() {
        prefs.edit().putString("named_presets_v2", "broken{").commit()
        assertTrue(runCatching { seed("新方案", "new_") }.isFailure)
        assertEquals("broken{", prefs.getString("named_presets_v2", null))
    }
    @Test fun namedMenuLoadsCorrectSetAndRestoresSortingForNumbering() {
        val store = RenamePresetStore(context)
        store.save("自然编号", listOf(Rule(Kind.SERIALIZE, mode = "replace", width = 2)), RenameFileOrder.Settings(RenameFileOrder.Key.NATURAL))
        seed("文档前缀", "doc_")
        open(); click("batch_rename_load_preset"); menu("自然编号 · 1 条")
        await("natural numbered preview") {
            (tagged("batch_rename_before_0") as TextView).text.contains("照片2.txt") &&
                (tagged("batch_rename_after_0") as TextView).text.contains("01.txt")
        }
        click("batch_rename_sort_direction")
        await("descending renumbers identities") {
            (tagged("batch_rename_before_0") as TextView).text.contains("照片10.txt") &&
                (tagged("batch_rename_after_0") as TextView).text.contains("01.txt")
        }
        click("batch_rename_sort_files"); menu("选择顺序")
        click("batch_rename_sort_direction")
        await("selection restored") { (tagged("batch_rename_before_0") as TextView).text.contains("照片10.txt") }
        assertEquals(2, folder.listFiles()!!.size) // Preview/sorting must not rename anything.
    }
    @Test fun savingNamedSetThroughUiKeepsOtherSetAndCancelRestoresSourceControl() {
        seed("已有方案", "old_"); open()
        click("batch_rename_load_preset"); menu("已有方案 · 1 条")
        await("loaded prefix") { (tagged("batch_rename_after_0") as TextView).text.contains("old_") }
        click("batch_rename_save_preset")
        main { (tagged("batch_rename_preset_title") as EditText).setText("第二套方案") }
        click("batch_rename_preset_commit")
        await("named set persisted") { RenamePresetStore(context).list().size == 2 }
        click("batch_rename_load_preset"); menu("第二套方案 · 1 条")
        await("correct named label") { (tagged("batch_rename_preset_name") as TextView).text.contains("第二套方案") }
        click("batch_rename_add"); click("batch_rename_rule_cancel")
        await("source alpha restored after morph") { tagged("batch_rename_add").alpha == 1f }
        assertEquals(1f, main { tagged("batch_rename_add").scaleX })
    }
    @Test fun ruleTypesAndModesUseTheSameMorphingMenu() {
        open(); click("batch_rename_add"); click("batch_rename_rule_kind"); menu("编号")
        await("number fields") { all().any { it.tag == "batch_rename_rule_start" } }
        click("batch_rename_rule_mode"); menu("替换当前名称")
        main { (tagged("batch_rename_rule_name") as EditText).setText("旅行照片编号") }
        click("batch_rename_rule_save")
        await("named rule queue and numbered preview") {
            all().filterIsInstance<TextView>().any { it.text.contains("旅行照片编号") } &&
                (tagged("batch_rename_after_0") as TextView).text.contains("001.txt")
        }
    }
    @Test fun newInsertionDefaultsToSuffixBeforeTheProtectedExtension() {
        open(); click("batch_rename_add")
        assertTrue(main { (tagged("batch_rename_rule_mode") as TextView).text.startsWith("作为后缀") })
        main { (tagged("batch_rename_rule_text") as EditText).setText("_旅行") }
        click("batch_rename_rule_save")
        await("default suffix preview before extension") {
            (tagged("batch_rename_after_0") as TextView).text.contains("照片10_旅行.txt") &&
                (tagged("batch_rename_after_1") as TextView).text.contains("照片2_旅行.txt")
        }
        click("batch_rename_edit")
        assertTrue(main { (tagged("batch_rename_rule_mode") as TextView).text.startsWith("作为后缀") })
        click("batch_rename_rule_cancel")
    }
}
