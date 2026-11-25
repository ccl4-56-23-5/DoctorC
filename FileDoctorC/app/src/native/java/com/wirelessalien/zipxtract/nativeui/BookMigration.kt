package com.wirelessalien.zipxtract.nativeui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.wirelessalien.zipxtract.lan.LanFile
import java.io.File
import java.io.IOException

internal object BookMigration {
    const val SEND_ACTION = "local.sevenzip.android.action.SEND_BOOKDOCTOR_MIGRATION"
    const val MIME = "application/vnd.bookdoctorc.migration+zip"
    const val BOOK_PACKAGE = "local.bookdoctorc"
    private const val CACHE_DIRECTORY = "book-migration"
    private var initialized = false

    fun isMigration(name: String): Boolean = name.endsWith(".bdcm", ignoreCase = true)

    fun stage(context: Context, intent: Intent, check: () -> Unit): File {
        require(intent.action == SEND_ACTION && intent.type == MIME) { "迁移文件类型不匹配" }
        require(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) { "迁移文件需要只读授权" }
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val clip = intent.clipData
        require(uri?.scheme == "content" && !uri.authority.isNullOrBlank() &&
            clip?.itemCount == 1 && clip.getItemAt(0).uri == uri) { "请选择一份迁移文件" }

        val (name, size) = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex < 0 || cursor.isNull(nameIndex)) return@use null
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
            cursor.getString(nameIndex) to size
        } ?: throw IOException("无法读取迁移文件信息")
        require(isMigration(name) && LanFile.validName(name) && IncomingFileNames.resolve(name, null) == name) {
            "迁移文件名无效"
        }
        val root = prepareCache(context)
        if (size >= 0 && size > root.usableSpace) throw IOException("暂存空间不足")
        val imported = FileHandoff(context).importContent(uri, name, root, check)
        if (size >= 0 && imported.length() != size) {
            discard(imported)
            throw IOException("迁移文件读取不完整")
        }
        return imported
    }

    fun viewIntent(context: Context, file: File): Intent {
        val item = FileHandoff(context).outgoing(file, preferMediaStore = false)
        return Intent(Intent.ACTION_VIEW).apply {
            setPackage(BOOK_PACKAGE)
            setDataAndType(item.uri, MIME)
            putExtra(Intent.EXTRA_TITLE, item.displayName)
            clipData = ClipData(item.displayName, arrayOf(MIME), ClipData.Item(item.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun discard(staged: File) {
        val folder = requireNotNull(staged.parentFile)
        staged.delete()
        folder.delete()
    }

    @Synchronized
    private fun prepareCache(context: Context): File {
        val cache = context.cacheDir.canonicalFile
        val root = File(cache, CACHE_DIRECTORY).canonicalFile
        check(root.parentFile == cache && (root.isDirectory || root.mkdir())) { "无法创建迁移暂存目录" }
        if (!initialized) {
            // Active transfers only live within a process, so this directory starts with abandoned copies.
            root.listFiles()?.forEach { dir ->
                if (dir.canonicalFile == dir && dir.isDirectory) {
                    dir.listFiles()?.filter { it.isFile }?.forEach { discard(it) }
                    dir.delete()
                }
            }
            initialized = true
        }
        return root
    }
}
