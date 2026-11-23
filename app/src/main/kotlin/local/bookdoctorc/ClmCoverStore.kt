package local.bookdoctorc

import android.util.AtomicFile
import com.example.clmplayer.LibraryItem
import com.example.clmplayer.LibraryScanner
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Stores the authenticated embedded image bytes in app-private files, with one revision per source. */
internal class ClmCoverStore(private val directory: File) {
    data class Artwork(val title: String, val image: ByteArray)
    private data class Header(val size: Long, val modified: Long, val title: String, val length: Int) {
        fun matches(item: LibraryItem): Boolean =
            (item.bytes <= 0 || item.bytes == size) && (item.modified <= 0 || item.modified == modified)
    }

    private var bytes = -1L
    private var count = 0

    fun read(item: LibraryItem): Artwork? {
        val path = file(item)
        val artwork = readEntry(path) { input, header ->
            if (!header.matches(item)) return@readEntry null
            Artwork(header.title, ByteArray(header.length).also(input::readFully))
        } ?: return null
        val now = System.currentTimeMillis()
        if (now - path.lastModified() > 60_000) path.setLastModified(now)
        return artwork
    }

    fun write(item: LibraryItem, artwork: Artwork, replace: Boolean = false) {
        if (artwork.image.size !in 1..MAX_IMAGE || !directory.isDirectory && !directory.mkdirs()) return
        val path = file(item)
        if (!replace && path.isFile && readEntry(path) { _, header -> header.matches(item) } == true) return
        if (bytes < 0) trim()
        val previousLength = path.length()
        val atomic = AtomicFile(path)
        try {
            val stream = atomic.startWrite()
            try {
                val output = DataOutputStream(stream)
                output.writeInt(FORMAT)
                output.writeLong(item.bytes)
                output.writeLong(item.modified)
                output.writeUTF(artwork.title.take(1024))
                output.writeInt(artwork.image.size)
                output.write(artwork.image)
                output.flush()
                atomic.finishWrite(stream)
            } catch (error: IOException) {
                atomic.failWrite(stream)
                throw error
            }
        } catch (_: IOException) {
            return
        }
        if (previousLength == 0L) count++
        bytes += path.length() - previousLength
        if (bytes > MAX_BYTES || count > MAX_FILES) trim()
    }

    private fun <T> readEntry(path: File, read: (DataInputStream, Header) -> T?): T? {
        if (!path.isFile) return null
        return try {
            DataInputStream(AtomicFile(path).openRead()).use { input ->
                if (input.readInt() != FORMAT) return null
                val header = Header(input.readLong(), input.readLong(), input.readUTF(), input.readInt())
                if (header.length !in 1..MAX_IMAGE || input.available() != header.length) return null
                read(input, header)
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun file(item: LibraryItem): File {
        val key = LibraryScanner.sourceId(item.uri)
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(directory, hash.joinToString("") { "%02x".format(it) } + ".cover")
    }

    private fun trim() {
        val cached = directory.listFiles { path -> path.isFile && path.name.endsWith(".cover") }.orEmpty()
        bytes = cached.sumOf(File::length)
        count = cached.size
        if (bytes <= MAX_BYTES && count <= MAX_FILES) return
        for (path in cached.sortedBy(File::lastModified)) {
            val length = path.length()
            if (path.delete()) {
                bytes -= length
                count--
            }
            if (bytes <= MAX_BYTES && count <= MAX_FILES) return
        }
    }

    private companion object {
        const val FORMAT = 0x434C4D01
        const val MAX_IMAGE = 8 * 1024 * 1024
        const val MAX_FILES = 768
        const val MAX_BYTES = 128L * 1024 * 1024
    }
}
