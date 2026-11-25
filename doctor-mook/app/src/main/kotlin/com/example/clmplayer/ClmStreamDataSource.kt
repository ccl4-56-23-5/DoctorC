package com.example.clmplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** A seekable view of one encrypted entry. No extraction, file copy or disk cache. */
@UnstableApi
internal class ClmStreamDataSource(private val container: ClmContainer, private val entry: ClmEntry) : BaseDataSource(false) {
    private var reader: ClmContainer.EntryReader? = null
    private var openedUri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.position !in 0..entry.length) throw IOException("音轨读取位置越界")
        val available = entry.length - dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
        position = dataSpec.position
        reader = container.reader(entry)
        openedUri = dataSpec.uri
        transferStarted(dataSpec)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val source = reader ?: throw IOException("音轨尚未打开")
        val count = source.readAt(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count <= 0) throw IOException("音轨意外截断")
        position += count; remaining -= count
        bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = openedUri
    override fun close() {
        val wasOpen = reader != null
        reader?.close(); reader = null; openedUri = null; remaining = 0
        if (wasOpen) transferEnded()
    }
}
