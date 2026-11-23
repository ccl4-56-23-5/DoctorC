package com.wirelessalien.zipxtract.nativeui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * File/content-URI handoff only. URI escaping is transport syntax, not a filename encoding bug.
 * Consumers must query DISPLAY_NAME; an external app that prints Uri.toString() may still show %XX.
 * Call the resolver/copy methods on an IO dispatcher. No media rows are inserted or rescanned here.
 */
class FileHandoff(private val context: Context) {
    data class Outgoing(val uri: Uri, val displayName: String, val mimeType: String)

    private val resolver get() = context.contentResolver
    private val authority get() = "${context.packageName}.provider"

    fun outgoing(file: File, preferMediaStore: Boolean = true): Outgoing {
        require(file.isFile) { "分享文件不存在" }
        val uri = (if (preferMediaStore) existingMediaUri(file) else null)
            ?: FileProvider.getUriForFile(context, authority, file, file.name)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"
        return Outgoing(uri, file.name, mime)
    }

    /**
     * Query only an exact, bound canonical DATA path. Never guess from DISPLAY_NAME / RELATIVE_PATH,
     * never use LIKE, decode a path, create a row, or request broader storage permissions.
     * Missing DATA, duplicate rows, denied access, stale rows and unreadable descriptors all fall back.
     */
    @Suppress("DEPRECATION")
    fun existingMediaUri(file: File): Uri? = try {
        val canonical = file.canonicalFile
        val collection = MediaStore.Files.getContentUri("external")
        val rows = ArrayList<MediaStoreFileMatch.Row>(2)
        resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DATA,
                MediaStore.Files.FileColumns.MEDIA_TYPE),
            "${MediaStore.MediaColumns.DATA} = ?", arrayOf(canonical.path), null
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val data = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val type = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (cursor.moveToNext()) {
                rows += MediaStoreFileMatch.Row(cursor.getLong(id), cursor.getString(data), cursor.getInt(type))
                if (rows.size > 1) break
            }
        }
        val row = MediaStoreFileMatch.unique(canonical, rows)?.takeIf {
            // Keep documents / archives on FileProvider; this adaptation targets gallery media only.
            it.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ||
                it.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ||
                it.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
        }
        if (row == null) null else {
            val typedCollection = when (row.mediaType) {
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> collection
            }
            val uri = ContentUris.withAppendedId(typedCollection, row.id)
            // Revalidate the actual typed URI too; a stale type / volume row must never select a peer.
            val sameFile = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                val data = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                if (!cursor.moveToFirst()) false else {
                    val match = MediaStoreFileMatch.unique(canonical,
                        listOf(row.copy(data = cursor.getString(data)))) != null
                    match && !cursor.moveToNext()
                }
            } ?: false
            // Access to a filesystem path (including all-files access) is not proof of URI readability.
            val readable = sameFile && (resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.let { it < 0L || it == canonical.length() }
            } ?: false)
            uri.takeIf { readable }
        }
    } catch (_: Exception) {
        null
    }

    fun viewIntent(file: File, preferMediaStore: Boolean = true): Intent {
        val item = outgoing(file, preferMediaStore)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mimeType)
            putExtra(Intent.EXTRA_TITLE, item.displayName)
            clipData = clip(listOf(item))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun shareIntent(files: List<File>, preferMediaStore: Boolean = true): Intent? {
        val items = files.filter { it.isFile }.map { outgoing(it, preferMediaStore) }
        if (items.isEmpty()) return null
        return Intent(if (items.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = items.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
            if (items.size == 1) putExtra(Intent.EXTRA_STREAM, items[0].uri)
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
            putExtra(Intent.EXTRA_TITLE, items[0].displayName)
            putExtra(Intent.EXTRA_SUBJECT, items[0].displayName)
            clipData = clip(items)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun clip(items: List<Outgoing>) = ClipData(
        ClipDescription(items[0].displayName, items.map { it.mimeType }.distinct().toTypedArray()),
        ClipData.Item(items[0].uri)
    ).apply { items.drop(1).forEach { addItem(ClipData.Item(it.uri)) } }

    fun incomingName(uri: Uri): String {
        val displayName = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
            }
        } catch (_: Exception) {
            null
        }
        // Neither metadata nor the already-decoded lastPathSegment is passed through a decoder.
        return IncomingFileNames.resolve(displayName, uri.lastPathSegment)
    }

    fun importFile(uri: Uri): File {
        if (uri.scheme == "file") return File(requireNotNull(uri.path))
        require(uri.scheme == "content") { "不支持的分享 URI" }
        return importContent(uri, incomingName(uri), File(context.cacheDir, "imports"))
    }

    internal fun importContent(uri: Uri, name: String, directory: File, check: () -> Unit = {}): File {
        require(uri.scheme == "content") { "不支持的分享 URI" }
        val imports = directory.canonicalFile
        val folder = File(imports, UUID.randomUUID().toString()).canonicalFile
        check(folder.parentFile == imports && (folder.mkdirs() || folder.isDirectory)) { "无法创建导入目录" }
        val output = IncomingFileNames.destination(folder, name, null)
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("无法读取分享文件")
            input.use {
                output.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
            }
            check()
            return output
        } catch (e: Exception) {
            // Both paths have just been canonicalized and constrained to this unique private folder.
            if (output.parentFile == folder) output.delete()
            if (folder.parentFile == imports) folder.delete()
            throw e
        }
    }
}
