package com.wirelessalien.zipxtract.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wirelessalien.zipxtract.nativeui.FileHandoff
import com.wirelessalien.zipxtract.nativeui.fileMimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

internal enum class PreviewKind { PHOTO, VIDEO }

internal fun previewKind(mime: String): PreviewKind? = when {
    mime.startsWith("image/") -> PreviewKind.PHOTO
    mime.startsWith("video/") -> PreviewKind.VIDEO
    else -> null
}

internal data class PreviewItem(val name: String, val mime: String, val file: File?, val originalUri: Uri? = null) {
    fun uri(context: Context): Uri? = originalUri ?: runCatching {
        FileHandoff(context).outgoing(checkNotNull(file), preferMediaStore = false).uri
    }.getOrNull()
}

internal class MediaPreviewIntent private constructor() {
    data class Sequence(val items: List<PreviewItem>, val start: Int)

    companion object {
        private const val KEY = "preview_sequence"
        private const val START = "preview_start"
        private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
        private val sessionId = Regex("[0-9a-f-]{36}")

        suspend fun create(context: Context, selected: File, candidates: List<File>): Intent =
            withContext(Dispatchers.IO) {
                val item = FileHandoff(context).outgoing(selected, preferMediaStore = false)
                val kind = checkNotNull(previewKind(item.mimeType))
                Intent(context, MediaPreviewActivity::class.java).apply {
                    setDataAndType(item.uri, item.mimeType)
                    putExtra(Intent.EXTRA_TITLE, item.displayName)
                    val ordered = candidates.distinctBy { it.path }.filter { previewKind(fileMimeType(it)) == kind }
                    val start = ordered.indexOfFirst { it.path == selected.path }
                    if (start >= 0 && ordered.size > 1) {
                        store(context, ordered)?.let {
                            putExtra(KEY, it)
                            putExtra(START, start)
                        }
                    }
                }
            }

        suspend fun read(context: Context, intent: Intent): Sequence = withContext(Dispatchers.IO) {
            val uri = intent.data
            val mime = intent.type.orEmpty()
            if (uri?.scheme != "content" || previewKind(mime) == null) return@withContext Sequence(emptyList(), 0)
            val original = PreviewItem(intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty(), mime, null, uri)
            val id = intent.getStringExtra(KEY)?.takeIf(sessionId::matches)
                ?: return@withContext Sequence(listOf(original), 0)
            val file = File(directory(context), id)
            runCatching {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    val count = input.readInt()
                    require(count > 0 && count <= file.length() / 2)
                    val start = intent.getIntExtra(START, 0)
                    require(start in 0 until count)
                    val media = List(count) { position ->
                        val entry = File(input.readUTF())
                        if (position == start) original else PreviewItem(entry.name, fileMimeType(entry), entry)
                    }
                    Sequence(media, start)
                }
            }.getOrElse { Sequence(listOf(original), 0) }
        }

        private fun store(context: Context, files: List<File>): String? {
            val root = directory(context)
            val id = UUID.randomUUID().toString()
            val contents = File(root, id)
            return try {
                check(root.mkdirs() || root.isDirectory)
                val cutoff = System.currentTimeMillis() - KEEP_MS
                root.listFiles().orEmpty().filter { sessionId.matches(it.name) && it.lastModified() < cutoff }
                    .forEach { it.delete() }
                DataOutputStream(contents.outputStream().buffered()).use { output ->
                    output.writeInt(files.size)
                    files.forEach { output.writeUTF(it.path) }
                }
                id
            } catch (_: Exception) {
                contents.delete()
                null
            }
        }

        private fun directory(context: Context) = File(context.cacheDir, "preview-sequences")
    }
}
