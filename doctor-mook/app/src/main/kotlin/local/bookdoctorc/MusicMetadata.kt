package local.bookdoctorc

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.example.clmplayer.CoverDecoder
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
internal object MusicMetadata {
    data class Line(val millis: Long?, val text: String)
    data class Preview(val title: String, val artist: String, val duration: Long, val thumbnail: Bitmap?)
    data class Info(val title: String, val artist: String, val art: Bitmap?, val duration: Long, val lyrics: List<Line>,
                    val thumbnail: Bitmap? = art?.let(MusicMetadata::thumbnail), val lyricsComplete: Boolean = true) {
        val preview = Preview(title, artist, duration, thumbnail)
    }

    private fun thumbnail(cover: Bitmap): Bitmap {
        val ratio = THUMBNAIL_SIDE.toFloat() / maxOf(cover.width, cover.height)
        if (ratio >= 1f) return cover
        return Bitmap.createScaledBitmap(cover, (cover.width * ratio).toInt().coerceAtLeast(1),
            (cover.height * ratio).toInt().coerceAtLeast(1), true).apply { prepareToDraw() }
    }

    private const val TAG_LIMIT = 8 * 1024 * 1024
    private val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val shift = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    fun read(context: Context, song: MusicStore.Song, disk: MusicMetadataStore): Info {
        val saved = runCatching { disk.read(song) }.getOrNull()
        if (saved != null) {
            if (saved.lyricsComplete) return saved
            val lyrics = runCatching { tags(SourceFiles(context), song) }.getOrNull() ?: return saved
            return saved.copy(lyrics = lyrics, lyricsComplete = true).also { runCatching { disk.write(song, it) } }
        }
        var title = song.displayName
        var artist = song.folder.substringAfterLast('/')
        var duration = 0L
        var image: Bitmap? = null
        var complete = false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, song.uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val picture = retriever.embeddedPicture
            image = picture?.let { CoverDecoder.decode(it, COVER_SIDE) }?.apply { prepareToDraw() }
            complete = picture == null || image != null
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        val lyrics = runCatching { tags(SourceFiles(context), song) }.getOrNull()
        val info = Info(title, artist, image, duration, lyrics.orEmpty(), lyricsComplete = lyrics != null)
        if (complete) runCatching { disk.write(song, info) }
        return info
    }

    const val COVER_SIDE = 720
    const val THUMBNAIL_SIDE = 240

    fun text(value: String): List<Line> {
        val offset = shift.find(value)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-600_000, 600_000) ?: 0L
        val timed = value.lineSequence().flatMap { line ->
            val words = stamp.replace(line, "").trim()
            if (words.isEmpty()) emptySequence() else stamp.findAll(line).map {
                val (minute, second, fraction) = it.destructured
                val time = minute.toLong() * 60_000 + second.toLong() * 1000 + fraction.padEnd(3, '0').toLong()
                Line((time + offset).coerceAtLeast(0), words)
            }
        }.sortedBy { it.millis }.toList()
        return if (timed.isNotEmpty()) timed else value.trim().takeIf { it.isNotEmpty() }?.let { listOf(Line(null, it)) }.orEmpty()
    }

    private fun tags(files: SourceFiles, song: MusicStore.Song): List<Line> {
        if (song.name.endsWith(".m4a", true) || song.name.endsWith(".mp4", true)) {
            return ParcelFileDescriptor.AutoCloseInputStream(files.descriptor(song.uri)).use { input ->
                mp4Lyrics(input.channel, 0, input.channel.size(), 0)?.let(::text).orEmpty()
            }
        }
        return DataInputStream(files.read(song.uri).buffered()).use { input ->
            val head = bytes(input, 10)
            when (head.copyOfRange(0, 3).toString(Charsets.US_ASCII)) {
                "ID3" -> id3(head, input)
                else -> when (head.copyOfRange(0, 4).toString(Charsets.US_ASCII)) {
                    "fLaC" -> flac(head, input)
                    "OggS" -> ogg(head, input)
                    else -> emptyList()
                }
            }
        }
    }

    private fun id3(header: ByteArray, input: DataInputStream): List<Line> {
        val length = header.drop(6).fold(0) { size, value -> (size shl 7) or (value.toInt() and 127) }
        if (length !in 1..TAG_LIMIT) return emptyList()
        val data = header + bytes(input, length)
        val metadata = Id3Decoder().decode(data, data.size) ?: return emptyList()
        var fallback = emptyList<Line>()
        for (index in 0 until metadata.length()) {
            val frame = metadata[index]
            val found = when (frame) {
                is BinaryFrame -> if (frame.id == "SYLT" || frame.id == "SLT") synchronized(frame.data)
                    else if (frame.id == "USLT" || frame.id == "ULT") unsynchronized(frame.data) else emptyList()
                is TextInformationFrame -> if (frame.description?.contains("LYRICS", true) == true)
                    frame.values.flatMap(::text) else emptyList()
                is InternalFrame -> if (frame.description.contains("LYRICS", true)) text(frame.text) else emptyList()
                else -> emptyList()
            }
            if (found.any { it.millis != null }) return found
            if (fallback.isEmpty()) fallback = found
        }
        return fallback
    }

    private fun bytes(input: DataInputStream, size: Int) = ByteArray(size).also { input.readFully(it) }
    private fun string(data: ByteArray, start: Int, end: Int, encoding: Int) =
        String(data, start, end - start, when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }).trim('\u0000', '\uFEFF')

    private fun terminator(data: ByteArray, from: Int, encoding: Int): Pair<Int, Int> {
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        var index = from
        while (index + step <= data.size) {
            if (data[index] == 0.toByte() && (step == 1 || data[index + 1] == 0.toByte())) return index to index + step
            index += step
        }
        return data.size to data.size
    }

    private fun unsynchronized(data: ByteArray): List<Line> {
        if (data.size < 5) return emptyList()
        val encoding = data[0].toInt()
        val start = terminator(data, 4, encoding).second
        return text(string(data, start, data.size, encoding))
    }

    private fun synchronized(data: ByteArray): List<Line> {
        if (data.size < 7) return emptyList()
        val encoding = data[0].toInt()
        val millis = data[4].toInt() == 2
        var cursor = terminator(data, 6, encoding).second
        val result = mutableListOf<Line>()
        while (cursor + 4 < data.size && result.size < 20_000) {
            val (end, next) = terminator(data, cursor, encoding)
            if (next + 4 > data.size) break
            val line = string(data, cursor, end, encoding).trim()
            val time = ByteBuffer.wrap(data, next, 4).int.toLong() and 0xffffffffL
            if (line.isNotEmpty()) result += Line(if (millis) time else null, line)
            cursor = next + 4
        }
        return if (millis) result.sortedBy { it.millis } else result
    }

    private fun flac(head: ByteArray, input: DataInputStream): List<Line> {
        val prefix = java.io.ByteArrayInputStream(head, 4, 6)
        val data = DataInputStream(java.io.SequenceInputStream(prefix, input))
        var read = 0
        while (read < TAG_LIMIT) {
            val type = data.readUnsignedByte()
            val length = (data.readUnsignedByte() shl 16) or data.readUnsignedShort()
            read += 4 + length
            if (read > TAG_LIMIT) break
            if (type and 127 == 4) return comments(bytes(data, length), 0)
            skip(data, length)
            if (type and 128 != 0) break
        }
        return emptyList()
    }

    private fun ogg(head: ByteArray, input: DataInputStream): List<Line> {
        val data = DataInputStream(java.io.SequenceInputStream(java.io.ByteArrayInputStream(head), input))
        val packet = java.io.ByteArrayOutputStream()
        repeat(128) {
            val page = bytes(data, 27)
            if (!page.copyOfRange(0, 4).contentEquals("OggS".toByteArray())) return emptyList()
            val sizes = bytes(data, page[26].toInt() and 255)
            for (segment in sizes) {
                val count = segment.toInt() and 255
                if (packet.size() + count > TAG_LIMIT) return emptyList()
                packet.write(bytes(data, count))
                if (count == 255) continue
                val complete = packet.toByteArray()
                packet.reset()
                if (complete.size >= 8 && complete.copyOfRange(0, 8).contentEquals("OpusTags".toByteArray()))
                    return comments(complete, 8)
                if (complete.size >= 7 && complete[0] == 3.toByte() &&
                    complete.copyOfRange(1, 7).contentEquals("vorbis".toByteArray())) return comments(complete, 7)
            }
        }
        return emptyList()
    }

    private fun comments(data: ByteArray, begin: Int): List<Line> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(begin)
        val vendorLength = buffer.int
        if (vendorLength < 0 || vendorLength > buffer.remaining() - 4) return emptyList()
        buffer.position(buffer.position() + vendorLength)
        val count = buffer.int.coerceIn(0, 20_000)
        var fallback = emptyList<Line>()
        repeat(count) {
            val size = buffer.int
            if (size < 0 || size > buffer.remaining()) return fallback
            val start = buffer.position()
            val end = start + size
            val field = string(data, start, end, 3)
            buffer.position(end)
            val key = field.substringBefore('=').uppercase(Locale.ROOT)
            if (key in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS")) {
                val found = text(field.substringAfter('=', ""))
                if (found.any { it.millis != null }) return found
                if (fallback.isEmpty()) fallback = found
            }
        }
        return fallback
    }

    private fun skip(input: InputStream, length: Int) {
        var left = length.toLong()
        while (left > 0) {
            val count = input.skip(left)
            if (count > 0) left -= count else {
                check(input.read() != -1) { "音乐标签已截断" }
                left--
            }
        }
    }

    private fun buffer(channel: FileChannel, position: Long, count: Int): ByteBuffer? {
        val data = ByteBuffer.allocate(count)
        channel.position(position)
        while (data.hasRemaining()) if (channel.read(data) <= 0) return null
        data.flip()
        return data
    }

    private fun mp4Lyrics(channel: FileChannel, start: Long, end: Long, depth: Int): String? {
        if (depth > 8) return null
        var cursor = start
        var boxes = 0
        while (cursor >= 0 && end - cursor >= 8 && boxes++ < 20_000) {
            val header = buffer(channel, cursor, 8) ?: return null
            var size = header.int.toLong() and 0xffffffffL
            val kind = header.int
            var head = 8
            if (size == 1L) {
                size = buffer(channel, cursor + 8, 8)?.long ?: return null
                head = 16
            } else if (size == 0L) size = end - cursor
            if (size < head || size > end - cursor) return null
            val payload = cursor + head
            val limit = cursor + size
            when (kind) {
                0x6d6f6f76, 0x75647461, 0x696c7374, 0xa96c7972.toInt() ->
                    mp4Lyrics(channel, payload, limit, depth + 1)?.let { return it }
                0x6d657461 -> mp4Lyrics(channel, payload + 4, limit, depth + 1)?.let { return it }
                0x64617461 -> if (depth >= 4 && limit - payload in 9..TAG_LIMIT.toLong()) {
                    val parentType = buffer(channel, start - 4, 4)?.int
                    if (parentType == 0xa96c7972.toInt()) {
                        return buffer(channel, payload + 8, (limit - payload - 8).toInt())?.array()?.toString(Charsets.UTF_8)
                    }
                }
            }
            cursor = limit
        }
        return null
    }
}
