package local.bookdoctorc

import android.graphics.Bitmap
import android.os.Build
import android.util.AtomicFile
import com.example.clmplayer.CoverDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** App-private cache for the tags and display-sized artwork of each source revision. */
internal class MusicMetadataStore(private val directory: File) {
    private var bytes = -1L
    private var count = 0

    fun readPreview(song: MusicStore.Song): MusicMetadata.Preview? =
        readEntry(song, MusicMetadata.THUMBNAIL_SIDE) { metadata, thumbnail ->
            MusicMetadata.Preview(metadata.getString("title"), metadata.getString("artist"),
                metadata.getLong("duration"), thumbnail)
        }

    fun readArtwork(song: MusicStore.Song): Bitmap? =
        readEntry(song, MusicMetadata.COVER_SIDE) { _, cover -> cover }

    fun read(song: MusicStore.Song): MusicMetadata.Info? =
        readEntry(song, MusicMetadata.COVER_SIDE) { metadata, art ->
            val lyrics = metadata.getJSONArray("lyrics")
            val lines = List(lyrics.length()) { index ->
                val line = lyrics.getJSONArray(index)
                MusicMetadata.Line(if (line.isNull(0)) null else line.getLong(0), line.getString(1))
            }
            MusicMetadata.Info(metadata.getString("title"), metadata.getString("artist"), art,
                metadata.getLong("duration"), lines, lyricsComplete = metadata.optBoolean("lyricsComplete", true))
        }

    private fun <T> readEntry(song: MusicStore.Song, side: Int, decode: (JSONObject, Bitmap?) -> T?): T? {
        val path = file(song)
        val result = runCatching {
            val (header, artBytes) = readPayload(path) ?: return null
            val metadata = JSONObject(header.toString(Charsets.UTF_8))
            val art = if (artBytes.isNotEmpty()) {
                CoverDecoder.decode(artBytes, side)?.apply { prepareToDraw() } ?: return null
            } else null
            decode(metadata, art)
        }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        if (now - path.lastModified() > 60_000) path.setLastModified(now)
        return result
    }

    @Synchronized
    private fun readPayload(path: File): Pair<ByteArray, ByteArray>? {
        if (!path.isFile) return null
        return DataInputStream(AtomicFile(path).openRead().buffered()).use { input ->
            if (input.readInt() != FORMAT) return null
            val header = input.readBytesWithSize(MAX_METADATA)
            val image = input.readBytesWithSize(MAX_ART, allowEmpty = true)
            if (input.read() != -1) return null
            header to image
        }
    }

    fun write(song: MusicStore.Song, info: MusicMetadata.Info) {
        val lines = JSONArray()
        for (line in info.lyrics) lines.put(JSONArray().put(line.millis ?: JSONObject.NULL).put(line.text))
        val header = JSONObject()
            .put("title", info.title)
            .put("artist", info.artist)
            .put("duration", info.duration)
            .put("lyrics", lines)
            .put("lyricsComplete", info.lyricsComplete)
        val metadata = header.toString().toByteArray(Charsets.UTF_8)
        if (metadata.size > MAX_METADATA) return
        val art = ByteArrayOutputStream()
        if (info.art != null) {
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
                else Bitmap.CompressFormat.PNG
            if (!info.art.compress(format, 70, art) || art.size() > MAX_ART) return
        }
        store(file(song), metadata, art)
    }

    @Synchronized
    private fun store(path: File, metadata: ByteArray, art: ByteArrayOutputStream) {
        if (!directory.isDirectory && !directory.mkdirs()) return
        if (bytes < 0) trim()
        val oldSize = path.length()
        val atomic = AtomicFile(path)
        runCatching {
            val stream = atomic.startWrite()
            var complete = false
            try {
                val output = DataOutputStream(stream.buffered())
                output.writeInt(FORMAT)
                output.writeInt(metadata.size)
                output.write(metadata)
                output.writeInt(art.size())
                art.writeTo(output)
                output.flush()
                complete = true
            } finally {
                if (complete) atomic.finishWrite(stream) else atomic.failWrite(stream)
            }
        }
        val newSize = path.length()
        if (oldSize == 0L && newSize > 0L) count++
        bytes += newSize - oldSize
        if (count > MAX_ENTRIES || bytes > MAX_BYTES) trim()
    }

    private fun DataInputStream.readBytesWithSize(limit: Int, allowEmpty: Boolean = false): ByteArray {
        val size = readInt()
        if (size < 0 || size > limit || (!allowEmpty && size == 0)) throw IOException("Invalid cached field length")
        return ByteArray(size).also(::readFully)
    }

    private fun file(song: MusicStore.Song): File {
        val revision = listOf(song.key, song.bytes, song.modified, song.name, song.folder).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(revision.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".meta")
    }

    private fun trim() {
        val entries = directory.listFiles { path -> path.isFile && path.extension == "meta" }.orEmpty()
        count = entries.size
        bytes = entries.sumOf(File::length)
        if (count <= MAX_ENTRIES && bytes <= MAX_BYTES) return
        for (entry in entries.sortedBy(File::lastModified)) {
            val length = entry.length()
            if (!entry.delete()) continue
            count--
            bytes -= length
            if (count <= MAX_ENTRIES && bytes <= MAX_BYTES) break
        }
    }

    private companion object {
        const val FORMAT = 0x4D4F4F01
        const val MAX_ART = 3 * 1024 * 1024
        const val MAX_METADATA = 8 * 1024 * 1024
        const val MAX_ENTRIES = 2048
        const val MAX_BYTES = 128L * 1024 * 1024
    }
}
