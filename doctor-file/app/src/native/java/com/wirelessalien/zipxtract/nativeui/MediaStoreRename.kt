package com.wirelessalien.zipxtract.nativeui

import android.Manifest
import android.annotation.TargetApi
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * Preserves an existing MediaStore item, rather than renaming its bytes and asking a scanner to
 * delete/reinsert it. Only DISPLAY_NAME is written. DATE_ADDED/MODIFIED/TAKEN, EXIF, MIME_TYPE and
 * filesystem mtime are never manufactured here. Provider permission is NOT implied by All Files.
 *
 * Call off the UI thread. This is a checked, best-effort transaction, not protection against an
 * arbitrary concurrent scanner/other process or provider bugs. A timestamp changed by the provider
 * is reported as a failure, not "repaired" by writing a date. API26-28 indexed items are rejected.
 * Shared directories require a complete, escaped subtree query with zero rows; app-private/app-
 * specific directories are unindexed. Unindexed files/directories use NIO without replacement.
 */
@TargetApi(26)
@Suppress("DEPRECATION")
object MediaStoreRename {
    data class Preparation(val canExecute: Boolean, val errors: List<String>)

    private val lock = Any()
    private val noFollow get() = arrayOf(LinkOption.NOFOLLOW_LINKS)
    private val externalFiles get() = MediaStore.Files.getContentUri("external")

    /** Read-only diagnostic preflight; it is NOT a lease. execute always prepares again. */
    fun prepare(context: Context, plan: BatchRenameEngine.Plan): Preparation = try {
        prepareSession(context.applicationContext, plan)
        Preparation(true, emptyList())
    } catch (error: Exception) {
        Preparation(false, listOf(explanation(error)))
    }

    fun execute(context: Context, plan: BatchRenameEngine.Plan): BatchRenameEngine.Result = synchronized(lock) {
        val session = try {
            prepareSession(context.applicationContext, plan)
        } catch (error: Exception) {
            return@synchronized BatchRenameEngine.Result(false, 0,
                "媒体库执行前检查失败，尚未移动文件：${explanation(error)}", false, emptyList())
        }
        val result = BatchRenameEngine.executeWithMover(plan, session::move)
        session.verifyResult(result)
    }

    /**
     * Single-file rename for the row menu, using exactly the same checked transaction as the batch
     * panel so an indexed item keeps its MediaStore row and all of its dates. Directories are NOT
     * handled here; the caller keeps the previous path for folders.
     */
    fun renameSingle(context: Context, file: File, newName: String): BatchRenameEngine.Result {
        val wanted = newName.trim()
        if (wanted.isEmpty()) return BatchRenameEngine.Result(false, 0, "名称不能为空", false, emptyList())
        if (wanted.contains('/') || wanted.contains('\\'))
            return BatchRenameEngine.Result(false, 0, "名称不能包含路径分隔符", false, emptyList())
        val oldName = file.name
        val oldExtension = extensionOf(oldName)
        val newExtension = extensionOf(wanted)
        val base = if (newExtension.isEmpty()) wanted else wanted.dropLast(newExtension.length)
        if (base.isEmpty()) return BatchRenameEngine.Result(false, 0, "名称不能为空", false, emptyList())
        val rules = ArrayList<RenamerRules.Rule>()
        // Reproduce the requested name exactly: clear the base, then write the new one; the owned
        // extension is edited separately so ".jpg" is never treated as part of the base name.
        rules += RenamerRules.Rule(RenamerRules.Kind.DELETE, mode = "all", skipExtension = true)
        rules += RenamerRules.Rule(RenamerRules.Kind.USER_INPUT, mode = "replace", text = base, skipExtension = true)
        if (newExtension != oldExtension) {
            rules += if (newExtension.isEmpty())
                RenamerRules.Rule(RenamerRules.Kind.EXTENSION, mode = "remove")
            else RenamerRules.Rule(RenamerRules.Kind.EXTENSION, mode = "replace", text = newExtension)
        }
        val plan = BatchRenameEngine.preview(listOf(file), BatchRenameEngine.Options(rules = rules))
        val errors = (plan.errors + plan.entries.flatMap { it.errors }).distinct()
        if (!plan.canExecute) return BatchRenameEngine.Result(false, 0,
            errors.joinToString("\n").ifEmpty { "名称无效" }, false, emptyList())
        return execute(context, plan)
    }

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot).lowercase() else ""
    }

    private data class Row(        val uri: Uri, val id: Long, val volume: String, val path: File, val name: String,
        val added: Long?, val modified: Long?, val taken: Long?, val mime: String?, val mediaType: Long?,
        val pending: Long?, val trashed: Long?
    )

    private data class Item(
        val entry: BatchRenameEngine.Entry, val key: Any, val size: Long, val modified: FileTime,
        val parent: File, val privateData: Boolean, val row: Row?, var current: File
    )

    private fun prepareSession(context: Context, plan: BatchRenameEngine.Plan): Session {
        if (Build.VERSION.SDK_INT < 26 || !BatchRenameEngine.isSupported())
            throw IOException("需要 Android 8/API26 或更新版本")
        if (!plan.canExecute) throw IOException((plan.errors + plan.entries.flatMap { it.errors })
            .distinct().joinToString("\n").ifEmpty { "没有可执行的重命名项目" })
        val items = plan.entries.filter { it.changed }.map { entry ->
            val source = entry.source.canonicalFile
            val attrs = attributes(source)
            if (!attrs.isRegularFile || attrs.isSymbolicLink)
                throw IOException("暂不重命名目录或非普通文件，无法保证所有子项媒体库时间：${source.name}")
            val key = attrs.fileKey() ?: throw IOException("无法核实文件身份：${source.path}")
            val parent = source.parentFile.canonicalFile
            if (entry.target.parentFile.canonicalFile != parent) throw IOException("目标必须仍在原目录")
            // MediaStore cannot index an app's internal /data/user/... storage. Do NOT use this
            // exemption for Android/data external storage: old/provider-specific rows may exist.
            val privateData = isWithin(source, context.dataDir.canonicalFile)
            if (!privateData) requireCompleteVisibility(context)
            val rows = if (privateData) emptyList() else query(context, externalFiles,
                "${MediaStore.MediaColumns.DATA} = ?", arrayOf(source.path))
            if (rows.size > 1) throw IOException("同一路径有多个媒体库条目，拒绝猜测：${source.path}")
            val row = rows.singleOrNull()
            if (row != null) {
                if (Build.VERSION.SDK_INT < 29)
                    throw IOException("Android 8–9 无法安全迁移已有媒体库条目：${source.name}")
                if (row.path != source || row.name != source.name)
                    throw IOException("媒体库名称/路径与文件不一致：${source.name}")
                if (row.pending != 0L || (Build.VERSION.SDK_INT >= 30 && row.trashed != 0L))
                    throw IOException("媒体库条目尚未完成或已在回收站：${source.name}")
                validateIndexedName(source.name, entry.target.name)
            }
            Item(entry, key, attrs.size(), attrs.lastModifiedTime(), parent, privateData, row, source)
        }
        if (items.map { it.key }.toSet().size != items.size)
            throw IOException("多个源项目具有相同文件身份，拒绝重复移动")
        // A stale target row can exist even when its bytes do not. Do not let provider.update
        // overwrite, merge, or remove it. Rows belonging to other selected sources will stage away.
        val selectedRows = items.mapNotNull { it.row?.uri }.toSet()
        for (item in items) if (!item.privateData) {
            val targets = query(context, externalFiles, "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(item.entry.target.canonicalFile.path))
            if (targets.any { it.uri !in selectedRows })
                throw IOException("目标名称已被其他媒体库条目占用：${item.entry.target.name}")
        }
        return Session(context, items)
    }

    private class Session(private val context: Context, private val items: List<Item>) {
        private val byPath = items.associateBy { it.current.path }.toMutableMap()
        private var failing = false

        fun move(from: File, to: File) {
            val source = from.absoluteFile
            val target = to.absoluteFile
            val item = byPath[source.path]?.takeIf { sameIdentity(source, it) }
                ?: items.singleOrNull { sameIdentity(source, it) }
                ?: throw IOException("无法将移动源对应到预检身份：${source.path}")
            try {
                if (target.parentFile.canonicalFile != item.parent)
                    throw IOException("移动目标超出原父目录")
                if (Files.exists(target.toPath(), *noFollow)) throw IOException("目标已存在，拒绝覆盖：${target.path}")
                verifyFile(source, item, strictTime = !failing)
                if (item.row == null) {
                    // Never raw-rename an item that became indexed after the preflight.
                    if (!item.privateData) {
                        requireCompleteVisibility(context)
                        if (rowsAt(source).isNotEmpty() || rowsAt(target).isNotEmpty())
                            throw IOException("预检后源或目标新增媒体库条目，请重新预览")
                    }
                    Files.move(source.toPath(), target.toPath())
                } else {
                    validateIndexedName(item.entry.source.name, target.name)
                    requireCompleteVisibility(context)
                    val before = readOriginal(item.row)
                    if (before.path != source) throw IOException("原媒体库条目不再位于移动源：${source.path}")
                    verifyRow(before, item.row, strictDates = !failing)
                    if (rowsAt(target).isNotEmpty()) throw IOException("目标媒体库条目已出现，拒绝覆盖")
                    val changed = context.contentResolver.update(item.row.uri,
                        ContentValues(1).apply { put(MediaStore.MediaColumns.DISPLAY_NAME, target.name) }, null, null)
                    if (changed != 1) throw IOException("媒体库更新未恰好命中原条目：$changed")
                }
                verifyFile(target, item, strictTime = true)
                if (item.row != null) {
                    val after = readOriginal(item.row)
                    if (after.path != target || after.name != target.name)
                        throw IOException("媒体库实际名称/路径不同于请求，不能宣告成功")
                    verifyRow(after, item.row, strictDates = true)
                    val matches = rowsAt(target)
                    if (matches.size != 1 || matches.single().uri != item.row.uri)
                        throw IOException("移动后路径未唯一对应原媒体库条目")
                } else if (!item.privateData && rowsAt(target).isNotEmpty()) {
                    throw IOException("移动期间出现新媒体库条目，不能确认原入库状态")
                }
            } catch (error: Exception) {
                // Further calls are the engine's best-effort rollback. Permit restoring names
                // when dates already drifted, but never write/forge them or report full rollback.
                failing = true
                throw error
            } finally {
                // update may move the bytes and then throw (including permission/provider errors).
                // Keep the same item URI; refresh location even on failure for rollback/reporting.
                reconcile(item, source, target)
            }
        }

        private fun rowsAt(file: File) = query(context, externalFiles,
            "${MediaStore.MediaColumns.DATA} = ?", arrayOf(file.path))

        private fun readOriginal(row: Row): Row {
            val rows = query(context, row.uri)
            if (rows.size != 1) throw IOException("原媒体库条目已消失或无法唯一查询：${row.uri}")
            return rows.single()
        }

        private fun reconcile(item: Item, from: File, to: File) {
            val rowPath = item.row?.let { runCatching { readOriginal(it).path }.getOrNull() }
            val found = listOfNotNull(rowPath, to, from, item.current).distinct()
                .firstOrNull { sameIdentity(it, item) } ?: return
            byPath.entries.removeAll { it.value === item }
            item.current = found
            byPath[found.path] = item
        }

        fun verifyResult(result: BatchRenameEngine.Result): BatchRenameEngine.Result {
            val failures = mutableListOf<String>()
            val locations = items.map { item ->
                reconcile(item, item.entry.source, item.entry.target)
                val current = item.current.takeIf { sameIdentity(it, item) }
                try {
                    if (current == null) throw IOException("无法核实文件的实际位置")
                    verifyFile(current, item, strictTime = true)
                    if (item.row != null) {
                        val row = readOriginal(item.row)
                        verifyRow(row, item.row, strictDates = true)
                        if (row.path != current || row.name != current.name)
                            throw IOException("原媒体库条目与实际文件位置不一致")
                        val rows = rowsAt(current)
                        if (rows.size != 1 || rows.single().uri != item.row.uri)
                            throw IOException("实际路径未唯一对应原条目")
                    } else if (!item.privateData && rowsAt(current).isNotEmpty()) {
                        throw IOException("未索引文件执行后出现媒体库条目")
                    }
                    if (result.success && current != item.entry.target)
                        throw IOException("实际位置不是最终目标")
                    if (result.rolledBack && current != item.entry.source)
                        throw IOException("实际位置未恢复原名")
                } catch (error: Exception) { failures += "${item.entry.source.name}：${explanation(error)}" }
                BatchRenameEngine.Location(item.entry.source, item.entry.target, current,
                    current == item.entry.source)
            }
            if (failures.isEmpty()) return result.copy(locations = locations)
            val prefix = if (result.success) "执行后保留性核验失败" else result.message
            return result.copy(success = false, renamedCount = 0, rolledBack = false, locations = locations,
                message = prefix + "\n" + failures.distinct().joinToString("\n") +
                    "\n未写入伪造日期、重建媒体条目或修改 EXIF；名称回滚不代表媒体时间已恢复。")
        }
    }

    private fun verifyFile(file: File, item: Item, strictTime: Boolean) {
        val attrs = attributes(file)
        if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.fileKey() != item.key || attrs.size() != item.size)
            throw IOException("文件身份或大小已变化：${file.path}")
        if (strictTime && attrs.lastModifiedTime() != item.modified)
            throw IOException("原文件修改时间未保留：${file.name}")
    }

    private fun sameIdentity(file: File, item: Item): Boolean = try {
        val attrs = attributes(file)
        attrs.isRegularFile && !attrs.isSymbolicLink && attrs.fileKey() == item.key && attrs.size() == item.size
    } catch (_: Exception) { false }

    private fun verifyRow(actual: Row, original: Row, strictDates: Boolean) {
        if (actual.uri != original.uri || actual.id != original.id || actual.volume != original.volume)
            throw IOException("媒体库条目身份发生变化")
        if (actual.mime != original.mime || actual.mediaType != original.mediaType ||
            actual.pending != original.pending || actual.trashed != original.trashed)
            throw IOException("媒体类型或条目状态发生变化")
        if (strictDates) {
            if (actual.added != original.added)
                throw IOException("原 DATE_ADDED（入库时间）未保留")
            if (actual.taken != original.taken)
                throw IOException("原 DATE_TAKEN（拍摄时间）未保留")
            // DATE_MODIFIED may legitimately be refreshed by the provider to the file's own
            // (unchanged) modification time when it rewrites the item during a rename. Rejecting
            // that would report a false failure; a jump to "now" or any other value is still
            // caught here, and this code never writes a date to "repair" it.
            if (actual.modified != original.modified) {
                val fileSeconds = runCatching {
                    attributes(actual.path).lastModifiedTime().toMillis() / 1000L
                }.getOrNull()
                val consistent = fileSeconds != null && actual.modified != null &&
                    kotlin.math.abs(actual.modified - fileSeconds) <= 1L
                if (!consistent)
                    throw IOException("原 DATE_MODIFIED 未保留，且与文件当前修改时间不一致")
            }
        }
    }

    private fun validateIndexedName(original: String, target: String) {
        if (original.startsWith('.') || target.startsWith('.'))
            throw IOException("已入库文件不支持隐藏名迁移，以免改变媒体类型/入库身份")
        if (target != target.trim() || target.endsWith('.') || target.any { it in "\\/:*?\"<>|" || it.code < 32 })
            throw IOException("目标名可能被媒体提供程序清洗，已在移动前拒绝")
        if (!File(original).extension.equals(File(target).extension, ignoreCase = true))
            throw IOException("已入库文件暂不支持更改扩展名，无法保证原媒体类型与时间")
    }

    private fun requireCompleteVisibility(context: Context) {
        val complete = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 29 || Environment.isExternalStorageLegacy())
        if (!complete) throw IOException("无法完整查询共享存储媒体库；请授予所有文件访问权限（旧系统需完整存储权限），不会静默改用原始重命名")
    }

    private fun query(context: Context, uri: Uri, selection: String? = null, args: Array<String>? = null): List<Row> {
        val projection = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED, "datetaken", MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE)
        if (Build.VERSION.SDK_INT >= 29) projection += listOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.IS_PENDING)
        if (Build.VERSION.SDK_INT >= 30) projection += MediaStore.MediaColumns.IS_TRASHED
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            val bundle = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                if (selection != null) putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (args != null) putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            }
            context.contentResolver.query(uri, projection.toTypedArray(), bundle, null)
        } else {
            val includingPending = if (Build.VERSION.SDK_INT >= 29) MediaStore.setIncludePending(uri) else uri
            context.contentResolver.query(includingPending, projection.toTypedArray(), selection, args, null)
        } ?: throw IOException("媒体库查询未返回游标，不能据此认定文件未入库")
        return cursor.use { c ->
            // Verify the projection even for an empty cursor, not just when a row exists.
            projection.forEach { c.getColumnIndexOrThrow(it) }
            val rows = mutableListOf<Row>()
            while (c.moveToNext()) {
                val id = c.longOrNull(MediaStore.MediaColumns._ID) ?: throw IOException("媒体库 ID 为空")
                val path = c.stringOrNull(MediaStore.MediaColumns.DATA) ?: throw IOException("媒体库路径不可见")
                val name = c.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME) ?: throw IOException("媒体库名称不可见")
                val volume = if (Build.VERSION.SDK_INT >= 29)
                    c.stringOrNull(MediaStore.MediaColumns.VOLUME_NAME)?.takeIf { it.isNotBlank() }
                        ?: throw IOException("媒体库卷身份不可见") else "external"
                rows += Row(ContentUris.withAppendedId(MediaStore.Files.getContentUri(volume), id), id, volume,
                    File(path).canonicalFile, name, c.longOrNull(MediaStore.MediaColumns.DATE_ADDED),
                    c.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED), c.longOrNull("datetaken"),
                    c.stringOrNull(MediaStore.MediaColumns.MIME_TYPE), c.longOrNull(MediaStore.Files.FileColumns.MEDIA_TYPE),
                    if (Build.VERSION.SDK_INT >= 29) c.longOrNull(MediaStore.MediaColumns.IS_PENDING) else null,
                    if (Build.VERSION.SDK_INT >= 30) c.longOrNull(MediaStore.MediaColumns.IS_TRASHED) else null)
            }
            rows
        }
    }

    private fun Cursor.longOrNull(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.stringOrNull(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun attributes(file: File): BasicFileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, *noFollow)
    private fun isWithin(file: File, directory: File): Boolean = file.path.startsWith(directory.path + File.separator)
    private fun explanation(error: Exception): String = if (error is SecurityException)
        "权限拒绝：媒体库读取/修改未获允许；所有文件访问不保证可修改原媒体条目。${error.message.orEmpty()}"
        else error.message ?: error.javaClass.simpleName
}
