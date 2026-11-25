package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Named-set disk commits run on the serial IO worker; last-used apply updates memory immediately. */
class RenamePresetStore(context: Context) {
    data class Preset(val id: String, val name: String, val rules: List<RenamerRules.Rule>,
                      val order: RenameFileOrder.Settings = RenameFileOrder.Settings(RenameFileOrder.Key.ORIGINAL))
    private val prefs = context.applicationContext.getSharedPreferences("batch_rename_rules", 0)
    data class LastUsed(val rules: List<RenamerRules.Rule>, val order: RenameFileOrder.Settings,
                        val presetId: String? = null, val name: String = "")

    fun lastUsed(): LastUsed? = synchronized(storeLock) {
        val raw = prefs.getString("last_used_v3", null)
        if (raw == null) {
            // Old builds did not track the last loaded set. Use the newest saved set once.
            return@synchronized list().lastOrNull()?.let { LastUsed(it.rules, it.order, it.id, it.name) }
        }
        val obj = JSONObject(raw)
        val id = obj.optString("id", "").takeIf { it.isNotEmpty() }
        val title = if (id == null) "" else list().firstOrNull { it.id == id }?.name.orEmpty()
        val order = RenameFileOrder.Settings(
            RenameFileOrder.Key.valueOf(obj.optString("sort", "ORIGINAL")), obj.optBoolean("descending", false))
        // Migrate the old implicit default for an unsaved draft. Named sets and other choices keep their order.
        val restoredOrder = if (!obj.optBoolean("capture_default_v1") && id == null &&
            order == RenameFileOrder.Settings(RenameFileOrder.Key.ORIGINAL)) RenameFileOrder.Settings() else order
        LastUsed(decodeRules(obj.getJSONArray("rules")), restoredOrder, id, title)
    }

    /** apply updates the in-process snapshot immediately, even if the panel reopens quickly. */
    fun remember(value: LastUsed) = synchronized(storeLock) {
        require(value.rules.size <= 64)
        val json = JSONObject().apply {
            put("id", value.presetId ?: ""); put("rules", encodeRules(value.rules))
            put("sort", value.order.key.name); put("descending", value.order.descending)
            put("capture_default_v1", true)
        }
        prefs.edit().putString("last_used_v3", json.toString()).apply()
    }

    fun list(): List<Preset> {
        val current = prefs.getString("named_presets_v2", null)
        if (current == null) {
            val legacy = prefs.getString("last_preset_v1", null) ?: return emptyList()
            return listOf(Preset("legacy-v1", "旧版规则", decodeRules(JSONArray(legacy))))
        }
        val array = JSONArray(current)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            Preset(obj.getString("id"), obj.getString("name"), decodeRules(obj.getJSONArray("rules")),
                RenameFileOrder.Settings(RenameFileOrder.Key.valueOf(obj.optString("sort", "ORIGINAL")), obj.optBoolean("descending", false)))
        }
    }

    /** Same-name writes update that set. Other saved sets are retained, including the migrated one. */
    fun save(name: String, rules: List<RenamerRules.Rule>, order: RenameFileOrder.Settings): Preset = synchronized(storeLock) {
        val title = name.trim()
        require(title.isNotEmpty() && title.codePointCount(0, title.length) <= 80) { "请输入 1—80 字的方案名称" }
        require(rules.isNotEmpty() && rules.size <= 64) { "方案需要 1—64 条规则" }
        val all = list().toMutableList()
        val index = all.indexOfFirst { it.name.equals(title, ignoreCase = true) }
        require(index >= 0 || all.size < 100) { "最多保存 100 套方案，请先删除不再需要的方案" }
        val preset = Preset(all.getOrNull(index)?.id ?: UUID.randomUUID().toString(), title, rules.toList(), order)
        if (index < 0) all.add(preset) else all[index] = preset
        write(all)
        preset
    }
    fun rename(id: String, name: String): Preset = synchronized(storeLock) {
        val title = name.trim()
        require(title.isNotEmpty() && title.codePointCount(0, title.length) <= 80) { "请输入 1—80 字的方案名称" }
        val all = list().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        require(index >= 0) { "方案已不存在，请重新载入" }
        require(all.none { it.id != id && it.name.equals(title, ignoreCase = true) }) { "该名称已被其他方案使用" }
        val updated = all[index].copy(name = title)
        all[index] = updated; write(all); updated
    }
    fun delete(id: String) = synchronized(storeLock) { write(list().filterNot { it.id == id }) }

    private fun write(all: List<Preset>) {
        val array = JSONArray()
        all.forEach { preset -> array.put(JSONObject().apply {
            put("id", preset.id); put("name", preset.name); put("sort", preset.order.key.name)
            put("descending", preset.order.descending); put("rules", encodeRules(preset.rules))
        }) }
        check(prefs.edit().putString("named_presets_v2", array.toString()).commit()) { "保存失败，请检查可用存储空间" }
        // Keep the old slot untouched; migration never destroys an existing user's only copy.
    }

    companion object {
        private val storeLock = Any()
        fun encodeRules(rules: List<RenamerRules.Rule>): JSONArray = JSONArray().apply {
            rules.forEach { r -> put(JSONObject().apply {
                put("kind", r.kind.name); put("name", r.name); put("enabled", r.enabled); put("skipExtension", r.skipExtension)
                put("text", r.text); put("replacement", r.replacement); put("mode", r.mode); put("position", r.position)
                put("count", r.count); put("ignoreCase", r.ignoreCase); put("start", r.start); put("step", r.step)
                put("width", r.width); put("dateFormat", r.dateFormat)
                if (r.kind == RenamerRules.Kind.DATE) put("dateSource", r.dateSource.name)
            }) }
        }
        fun decodeRules(array: JSONArray): List<RenamerRules.Rule> {
            require(array.length() <= 64) { "方案最多 64 条规则" }
            return (0 until array.length()).map { index ->
                val r = array.getJSONObject(index)
                RenamerRules.Rule(kind = RenamerRules.Kind.valueOf(r.getString("kind")), name = r.optString("name", ""),
                    enabled = r.optBoolean("enabled", true), skipExtension = r.optBoolean("skipExtension", true),
                    text = r.optString("text", ""), replacement = r.optString("replacement", ""), mode = r.optString("mode", "prefix"),
                    position = r.optInt("position", 1), count = r.optInt("count", 0), ignoreCase = r.optBoolean("ignoreCase", false),
                    start = r.optLong("start", 1), step = r.optLong("step", 1), width = r.optInt("width", 3),
                    dateFormat = r.optString("dateFormat", "yyyy-MM-dd HH.mm.ss"),
                    dateSource = RenamerRules.DateSource.values().firstOrNull { it.name == r.optString("dateSource") }
                        ?: RenamerRules.DateSource.MODIFIED)
            }
        }
    }
}
