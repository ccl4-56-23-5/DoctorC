package com.wirelessalien.zipxtract.nativeui

import android.annotation.TargetApi
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/**
 * Android adaptation of LiteLaunch's common rename rules, NOT its eighteen-rule Windows plugin.
 * No scripts, ExifTool/Windows metadata, presets, wildcard language, or persistent undo are imported.
 * Rules run in this order: replacement -> prefix/suffix -> optional _number -> protected extension.
 * The caller's list order is the numbering order; nothing is sorted or decoded implicitly.
 *
 * preview and execute both do filesystem IO and MUST run off the UI thread. Requires Android 8/API26
 * (java.nio.file); API24/25 callers must disable this feature, not fall back to File.renameTo.
 * Moves use Files.move WITHOUT REPLACE_EXISTING or ATOMIC_MOVE. The latter can replace a target.
 * This is best-effort rollback, not a crash journal or a cross-process filesystem transaction: arbitrary
 * concurrent external changes / all filesystem providers cannot be promised atomic no-overwrite.
 * No user file is deleted, copied, or deliberately replaced, including when rollback is incomplete.
 */
@TargetApi(26)
object BatchRenameEngine {
    data class Options(
        val find: String = "", val replace: String = "", val regex: Boolean = false,
        val ignoreCase: Boolean = false, val prefix: String = "", val suffix: String = "",
        val numbering: Boolean = false, val start: Long = 1L, val step: Long = 1L,
        val width: Int = 3, val keepExtension: Boolean = true,
        /** null retains the old programmatic API; the 1.0.9 UI always supplies an ordered chain. */
        val rules: List<RenamerRules.Rule>? = null
    )

    data class Entry(val source: File, val target: File, val errors: List<String>) {
        val changed: Boolean get() = source.name != target.name
    }

    class Plan internal constructor(entries: List<Entry>, errors: List<String>, internal val stamps: Map<String, Stamp>) {
        val entries: List<Entry> = immutable(entries.map { it.copy(errors = immutable(it.errors)) })
        val errors: List<String> = immutable(errors)
        val changedCount: Int get() = entries.count { it.changed }
        val canExecute: Boolean get() = changedCount > 0 && errors.isEmpty() && entries.all { it.errors.isEmpty() }
    }

    data class Location(val source: File, val target: File, val current: File?, val restored: Boolean)
    data class Result(val success: Boolean, val renamedCount: Int, val message: String,
                      val rolledBack: Boolean, val locations: List<Location>)

    internal data class Stamp(val source: File, val key: Any, val size: Long, val modified: FileTime,
                              val directory: Boolean, val parent: File, val parentKey: Any,
                              val identity: (File, BasicFileAttributes) -> Any?)
    private data class Draft(val source: File, val target: File, val stamp: Stamp?, val errors: MutableList<String>)
    private data class Work(val entry: Entry, val stamp: Stamp, val temporary: File, var current: File)
    private val operationLock = Any()
    private val noFollow get() = arrayOf(LinkOption.NOFOLLOW_LINKS)
    private const val MAX_RULE_TEXT = 4096

    /** Safe to use for feature detection; do not invoke preview/execute at all on Android API24/25. */
    fun isSupported(): Boolean = try { Class.forName("java.nio.file.Files"); true }
        catch (_: Exception) { false } catch (_: LinkageError) { false }

    fun preview(files: List<File>, options: Options = Options()): Plan =
        previewInternal(files, options) { _, attrs -> attrs.fileKey() }

    /** Per-plan test adapter only. Never changes the production reader or any global state. */
    internal fun previewForTesting(files: List<File>, options: Options = Options(),
                                   identity: (File, BasicFileAttributes) -> Any?): Plan =
        previewInternal(files, options, identity)

    private fun previewInternal(files: List<File>, options: Options,
                                identity: (File, BasicFileAttributes) -> Any?): Plan {
        val global = ArrayList<String>()
        val orderedRules = options.rules?.toList()
        val needsExif = orderedRules?.any {
            it.enabled && it.kind == RenamerRules.Kind.DATE && it.dateSource != RenamerRules.DateSource.MODIFIED
        } == true
        if (files.isEmpty()) global += "请先选择文件或文件夹"
        if (orderedRules != null) global += RenamerRules.validate(orderedRules)
        if (orderedRules == null && listOf(options.find, options.replace, options.prefix, options.suffix).any { it.length > MAX_RULE_TEXT })
            global += "规则文字过长（每项最多 $MAX_RULE_TEXT 字符）"
        if (orderedRules == null && options.numbering && (options.start < 0L || options.step <= 0L || options.width !in 0..18))
            global += "编号起始值须不小于0、步长须大于0、位数须为0—18"
        var pattern: Pattern? = null
        if (orderedRules == null && options.regex && options.find.isNotEmpty() && global.isEmpty()) {
            try { pattern = Pattern.compile(options.find, if (options.ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0) }
            catch (e: Exception) { global += "正则表达式无效：${e.message}" }
            catch (_: StackOverflowError) { global += "正则表达式过于复杂" }
        }
        val drafts = files.mapIndexed { index, input ->
            val errors = ArrayList<String>()
            var source = input.absoluteFile
            var stamp: Stamp? = null
            try {
                if (Files.isSymbolicLink(source.toPath())) throw IOException("不支持符号链接，请选择真实文件")
                source = source.toPath().toRealPath().toFile()
                stamp = snapshot(source, identity)
            } catch (e: Exception) { errors += "无法安全读取源项目：${e.message}" }
            var name = source.name
            if (global.isEmpty() && stamp != null) {
                try {
                    name = if (orderedRules == null) transform(name, stamp.directory, index, options, pattern)
                    else {
                        val capture = if (needsExif && !stamp.directory) MediaFacts.exifCaptureDate(source) else null
                        RenamerRules.apply(name, stamp.directory, index, stamp.modified.toMillis(), stamp.size, orderedRules, capture)
                    }
                }
                catch (e: Exception) { errors += "规则处理失败：${e.message}" }
                catch (_: StackOverflowError) { errors += "正则表达式过于复杂，请简化规则" }
            }
            invalidName(name)?.let { errors += it }
            val parent = stamp?.parent ?: source.parentFile
            if (parent == null) errors += "不能重命名存储根目录"
            Draft(source, if (parent == null) source else File(parent, name), stamp, errors)
        }
        validate(drafts)
        return Plan(drafts.map { Entry(it.source, it.target, it.errors) }, global,
            Collections.unmodifiableMap(drafts.mapNotNull { it.stamp }.associateBy { it.source.path }))
    }

    private fun snapshot(source: File, identity: (File, BasicFileAttributes) -> Any?): Stamp {
        val path = source.toPath()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, *noFollow)
        if (attrs.isSymbolicLink || (!attrs.isRegularFile && !attrs.isDirectory))
            throw IOException("仅支持普通文件和真实文件夹")
        val parent = source.parentFile ?: throw IOException("不能重命名存储根目录")
        val parentAttrs = Files.readAttributes(parent.toPath(), BasicFileAttributes::class.java, *noFollow)
        if (!parentAttrs.isDirectory || parentAttrs.isSymbolicLink || !Files.isWritable(parent.toPath()))
            throw IOException("父目录不可写或不是可验证的真实目录")
        return Stamp(source, identity(source, attrs) ?: throw IOException("文件系统未提供可靠文件身份"),
            attrs.size(), attrs.lastModifiedTime(), attrs.isDirectory, parent,
            identity(parent, parentAttrs) ?: throw IOException("无法取得父目录身份"), identity)
    }

    private fun transform(name: String, directory: Boolean, index: Int, o: Options, pattern: Pattern?): String {
        val dot = if (o.keepExtension && !directory) name.lastIndexOf('.').takeIf { it > 0 } ?: name.length else name.length
        val extension = name.substring(dot)
        var stem = name.substring(0, dot)
        if (o.find.isNotEmpty()) stem = if (pattern != null)
            pattern.matcher(BudgetText(stem, System.nanoTime() + 100_000_000L)).replaceAll(o.replace)
        else stem.replace(o.find, o.replace, ignoreCase = o.ignoreCase)
        stem = o.prefix + stem + o.suffix
        if (o.numbering) {
            val number = Math.addExact(o.start, Math.multiplyExact(index.toLong(), o.step))
            stem += "_" + number.toString().padStart(o.width, '0')
        }
        return stem + extension
    }

    /** Android leaves allow +, %, emoji, # and ?. Do not silently apply Windows filename rewriting. */
    private fun invalidName(name: String): String? = when {
        name.isBlank() || name == "." || name == ".." -> "名称不能为空、空白、. 或 .."
        name.any { it == '/' || it == '\\' || it.isISOControl() } -> "名称不能包含路径分隔符或控制字符"
        name.toByteArray(Charsets.UTF_8).size > 255 -> "名称超过255个UTF-8字节"
        hasUnpairedSurrogate(name) -> "名称包含不完整的Unicode字符"
        else -> null
    }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (Character.isHighSurrogate(c)) {
                if (i == value.length || !Character.isLowSurrogate(value[i++])) return true
            } else if (Character.isLowSurrogate(c)) return true
        }
        return false
    }

    private fun validate(drafts: List<Draft>) {
        fun mark(group: List<Draft>, error: String) { if (group.size > 1) group.forEach { it.errors += error } }
        drafts.groupBy { it.source.path }.values.forEach { mark(it, "重复选择同一源路径") }
        drafts.filter { it.stamp != null }.groupBy { it.stamp!!.key }.values.forEach { mark(it, "重复源文件身份（含硬链接）") }
        drafts.groupBy { collisionKey(it.target) }.values.forEach { mark(it, "多个项目生成相同目标名（含大小写/Unicode等价冲突）") }
        drafts.filter { it.stamp?.directory == true }.forEach { parent ->
            drafts.filter { it.stamp != null && it !== parent && it.source.toPath().startsWith(parent.source.toPath()) }.forEach { child ->
                parent.errors += "不能同时重命名文件夹和其子项目"
                child.errors += "不能同时重命名文件夹和其子项目"
            }
        }
        val sources = drafts.map { it.source }.toSet()
        drafts.filter { it.stamp != null && it.errors.isEmpty() }.forEach { draft ->
            try {
                val target = draft.target.toPath()
                if (draft.target.parentFile.canonicalFile != draft.stamp!!.parent)
                    throw IOException("目标超出原父目录")
                if (Files.exists(target, *noFollow)) {
                    if (Files.isSymbolicLink(target) || draft.target.canonicalFile !in sources)
                        draft.errors += "目标已存在，不能覆盖：${draft.target.name}"
                }
            } catch (e: Exception) { draft.errors += "无法验证目标：${e.message}" }
        }
    }

    private fun collisionKey(file: File): String = Normalizer.normalize(file.path, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /** Total progress is twice the changed count (stage then finalize). Callback exceptions are ignored. */
    fun execute(plan: Plan, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result = synchronized(operationLock) {
        executeInternal(plan, onProgress, ::moveNoReplace)
    }

    /** Catalog-aware Android backend. All writes and rollbacks use the SAME preflighted mover. */
    fun executeWithMover(plan: Plan, mover: (File, File) -> Unit): Result = synchronized(operationLock) {
        executeInternal(plan, { _, _ -> }, mover)
    }

    /** Fault injection for deterministic rollback regression tests; not used by the application UI. */
    internal fun executeForTesting(plan: Plan, onProgress: (Int, Int) -> Unit = { _, _ -> },
                                   move: (File, File) -> Unit): Result = synchronized(operationLock) {
        executeInternal(plan, onProgress, move)
    }

    private fun executeInternal(plan: Plan, progress: (Int, Int) -> Unit, move: (File, File) -> Unit): Result {
        if (!plan.canExecute) return Result(false, 0,
            (plan.errors + plan.entries.flatMap { it.errors }).distinct().joinToString("\n").ifEmpty { "没有需要重命名的项目" },
            false, emptyList())
        val checked = plan.entries.map { entry ->
            val errors = ArrayList<String>()
            val stamp = plan.stamps[entry.source.path]
            if (stamp == null || !matches(entry.source, stamp, true) || !sameParent(stamp))
                errors += "源文件身份、大小、时间或父目录已变化，请重新预览"
            Draft(entry.source, entry.target, stamp, errors)
        }
        validate(checked)
        val validationErrors = checked.flatMap { row -> row.errors.map { "${row.source.name}：$it" } }.distinct()
        if (validationErrors.isNotEmpty()) return Result(false, 0, validationErrors.joinToString("\n"), false, emptyList())
        val reserved = plan.entries.flatMap { listOf(collisionKey(it.source), collisionKey(it.target)) }.toMutableSet()
        val work = ArrayList<Work>()
        try {
            plan.entries.filter { it.changed }.forEach { entry ->
                val stamp = plan.stamps.getValue(entry.source.path)
                var temporary: File? = null
                repeat(64) {
                    if (temporary == null) {
                        // Keep the ORIGINAL extension and never start with a dot. A hidden or
                        // extension-less staging name would change what MediaProvider/FileManager
                        // consider this item to be, and an indexed row is rejected by Android for
                        // hidden names; the media backend then cannot preserve the original row.
                        val candidate = File(stamp.parent, stagingName(entry.source.name))
                        if (collisionKey(candidate) !in reserved && !Files.exists(candidate.toPath(), *noFollow)) {
                            reserved += collisionKey(candidate); temporary = candidate
                        }
                    }
                }
                work += Work(entry, stamp, temporary ?: throw IOException("无法分配安全临时名称"), entry.source)
            }
        } catch (e: Exception) { return Result(false, 0, "执行前检查失败：${e.message}", false, emptyList()) }
        var done = 0
        val total = work.size * 2
        fun report() { try { progress(done, total) } catch (_: Exception) { /* UI cannot abort a file transaction. */ } }
        report()
        try {
            for (item in work) {
                checkedMove(item.current, item.temporary, item.stamp, true, move)
                item.current = item.temporary; done++; report()
            }
            for (item in work) {
                checkedMove(item.current, item.entry.target, item.stamp, true, move)
                item.current = item.entry.target; done++; report()
            }
            val locations = immutable(work.map { Location(it.entry.source, it.entry.target, realFile(it.entry.target), false) })
            return Result(true, work.size, "已重命名 ${work.size} 个项目", false, locations)
        } catch (e: Exception) {
            return rollback(work, e, move)
        }
    }

    private fun checkedMove(from: File, to: File, stamp: Stamp, strict: Boolean, move: (File, File) -> Unit) {
        if (!sameParent(stamp) || !matches(from, stamp, strict)) throw IOException("项目或父目录已变化：${from.path}")
        if (to.parentFile.canonicalFile != stamp.parent) throw IOException("目标超出原父目录：${to.path}")
        if (Files.exists(to.toPath(), *noFollow)) throw IOException("目标已出现，拒绝覆盖：${to.path}")
        move(from, to)
        if (!matches(to, stamp, strict)) throw IOException("移动后无法核实文件身份：${to.path}")
        if (realFile(to).name != to.name) throw IOException("文件系统改变了目标名称：${to.path}")
    }

    private fun rollback(work: List<Work>, failure: Exception, move: (File, File) -> Unit): Result {
        fun locate(item: Work): File? = listOf(item.current, item.temporary, item.entry.target, item.entry.source)
            .distinctBy { it.path }.firstNotNullOfOrNull { file ->
                if (matches(file, item.stamp, false)) try { realFile(file) } catch (_: Exception) { null } else null
            }
        // First park finalized items again, otherwise a swap/cycle can block its own original paths.
        for (item in work.asReversed()) {
            val current = locate(item) ?: continue
            item.current = current
            if (current.path != item.entry.source.path && current.path != item.temporary.path) {
                try { checkedMove(current, item.temporary, item.stamp, false, move); item.current = item.temporary }
                catch (_: Exception) { /* Preserve the current file; never remove an occupied temporary. */ }
            }
        }
        // Multiple passes also restore independent items when one blocked original becomes available.
        for (pass in 0..work.size) {
            var moved = false
            for (item in work.asReversed()) {
                val current = locate(item) ?: continue
                item.current = current
                if (current.path == item.entry.source.path || occupiedOrUnknown(item.entry.source)) continue
                try { checkedMove(current, item.entry.source, item.stamp, false, move); item.current = item.entry.source; moved = true }
                catch (_: Exception) { /* A partial rollback is reported with exact recovery paths below. */ }
            }
            if (!moved) break
        }
        val locations = immutable(work.map { item ->
            val current = locate(item)
            Location(item.entry.source, item.entry.target, current, current?.path == item.entry.source.path)
        })
        val restored = locations.all { it.restored }
        val message = buildString {
            append("重命名失败：").append(failure.message ?: failure.javaClass.simpleName)
            if (restored) append("\n已还原本批全部名称。") else {
                append("\n部分项目未能还原；本工具未执行删除或主动覆盖。请保留以下文件并按路径处理：")
                locations.forEachIndexed { index, location ->
                    if (!location.restored) {
                        append("\n原路径：").append(location.source.path)
                        if (location.current != null) append("\n当前路径：").append(location.current.path)
                        else append("\n身份无法确认；检查临时路径：").append(work[index].temporary.path)
                            .append("\n及目标路径：").append(location.target.path)
                    }
                }
            }
        }
        return Result(false, locations.count { !it.restored && it.current?.path == it.target.path }, message, restored, locations)
    }

    private fun sameParent(stamp: Stamp): Boolean = try {
        val attrs = Files.readAttributes(stamp.parent.toPath(), BasicFileAttributes::class.java, *noFollow)
        attrs.isDirectory && !attrs.isSymbolicLink && stamp.identity(stamp.parent, attrs) == stamp.parentKey && realFile(stamp.parent).path == stamp.parent.path
    } catch (_: Exception) { false }

    private fun matches(file: File, stamp: Stamp, strict: Boolean): Boolean = try {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, *noFollow)
        !attrs.isSymbolicLink && stamp.identity(file, attrs) == stamp.key && attrs.isDirectory == stamp.directory &&
            (attrs.isDirectory || attrs.isRegularFile) &&
            (!strict || (attrs.size() == stamp.size && attrs.lastModifiedTime() == stamp.modified))
    } catch (_: Exception) { false }

    private fun occupiedOrUnknown(file: File): Boolean = try { Files.exists(file.toPath(), *noFollow) }
        catch (_: Exception) { true }

    private fun realFile(file: File): File = file.toPath().toRealPath().toFile()
    /**
     * Staging name that keeps the media identity: visible (no leading dot) and carrying the same
     * extension, so Android's file manager and MediaProvider still see the same kind of item.
     */
    private fun stagingName(original: String): String {
        val dot = original.lastIndexOf('.')
        val extension = if (dot > 0) original.substring(dot) else ""
        return "ZipDoctorC-rename-${UUID.randomUUID()}$extension"
    }
    private fun moveNoReplace(source: File, target: File) { Files.move(source.toPath(), target.toPath()) }
    private fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))

    /** Interrupt pathological backtracking without a leaked worker thread or unsafe Thread.stop. */
    private class BudgetText(private val text: String, private val deadline: Long) : CharSequence {
        private var reads = 0
        private fun checkBudget() {
            if (++reads % 128 == 0 && (System.nanoTime() > deadline || Thread.currentThread().isInterrupted))
                throw IllegalArgumentException("正则匹配超时或已取消，请简化表达式")
        }
        override val length: Int get() { checkBudget(); return text.length }
        override fun get(index: Int): Char { checkBudget(); return text[index] }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            checkBudget(); return BudgetText(text.substring(startIndex, endIndex), deadline)
        }
        override fun toString(): String { checkBudget(); return text }
    }
}
