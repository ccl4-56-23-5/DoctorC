package com.example.clmplayer

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.system.Os
import android.system.OsConstants
import local.bookdoctorc.ClmPassphrase
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.Base64

internal data class ClmTrack(val name: String, val entry: ClmEntry)
internal class ClmArchive(val id: String, val title: String, val cover: ByteArray, val tracks: List<ClmTrack>, val container: ClmContainer) : Closeable {
    override fun close() = container.close()
}
private class DocumentSource(private val afd: AssetFileDescriptor) : ClmSource {
    private val start = afd.startOffset
    override val size: Long
    init {
        try {
            Os.lseek(afd.fileDescriptor, 0, OsConstants.SEEK_CUR)
            size = if (afd.declaredLength >= 0) afd.declaredLength else afd.parcelFileDescriptor.statSize - start
            require(size > 0) { "文件长度不可用" }
        } catch (e: Exception) { afd.close(); throw IOException("此文件位置不支持直接随机读取，请选择手机本地存储中的 CLM", e) }
    }
    override fun readAt(position: Long, target: ByteArray, offset: Int, count: Int): Int =
        Os.pread(afd.fileDescriptor, target, offset, count, start + position)
    override fun close() = afd.close()
}
internal object ClmArchiveReader {
    private fun openContainer(context: Context, uri: Uri, cancellation: CancellationSignal): ClmContainer {
        cancellation.throwIfCanceled()
        val password = ClmPassphrase.read(context)
            ?: throw IOException("请先在「我的 → CLM 内容口令」中输入封装时使用的口令")
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r", cancellation) ?: error("无法读取 CLM")
        return ClmContainer(DocumentSource(afd), password)
    }

    private fun manifest(container: ClmContainer, cancellation: CancellationSignal): JSONObject {
        cancellation.throwIfCanceled()
        val value = JSONObject(container.manifest)
        require(value.getString("format") == "clm" && value.getInt("version") == 2) { "CLM 目录版本错误" }
        return value
    }

    fun title(context: Context, uri: Uri, cancellation: CancellationSignal): String =
        openContainer(context, uri, cancellation).use { manifest(it, cancellation).optString("title") }

    fun read(context: Context, uri: Uri, cancellation: CancellationSignal): ClmArchive {
        val container = openContainer(context, uri, cancellation)
        try {
            val manifest = manifest(container, cancellation)
            fun entry(json: JSONObject, ordinal: Int) = ClmEntry(
                json.getLong("offset"), json.getLong("length"),
                Base64.getDecoder().decode(json.getString("noncePrefix")), ordinal, json.getString("mime")
            )
            val cover = entry(manifest.getJSONObject("cover"), 0)
            require(cover.length in 1..8388608) { "封面大小无效" }
            val list = manifest.getJSONArray("tracks")
            require(list.length() in 1..100000) { "CLM 音轨数量无效" }
            val tracks = (0 until list.length()).map { i ->
                val value = list.getJSONObject(i)
                ClmTrack(value.getString("name"), entry(value, i + 1))
            }
            cancellation.throwIfCanceled()
            container.validate(listOf(cover) + tracks.map { it.entry })
            val bytes = ByteArray(cover.length.toInt())
            container.reader(cover).use { it.readAt(0, bytes, 0, bytes.size) }
            cancellation.throwIfCanceled()
            return ClmArchive(container.id, manifest.optString("title", "CLM 专辑"), bytes, tracks, container)
        } catch (e: Exception) { container.close(); throw e }
    }
}
