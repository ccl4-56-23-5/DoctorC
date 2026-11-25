package com.wirelessalien.zipxtract.lan

import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.webkit.MimeTypeMap
import androidx.annotation.Keep
import com.wirelessalien.zipxtract.nativeui.FileManagerKernel
import org.json.JSONObject
import java.io.EOFException
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal data class LanFile(
    val id: String,
    val name: String,
    val size: Long,
    val times: FileTimes,
    val mime: String
) {
    fun json() = JSONObject().put("id", id).put("fileName", name).put("size", size)
        .put("fileType", mime)
        .put("metadata", JSONObject().put("modified", times.modified).put("accessed", times.accessed))

    companion object {
        val identifier = Regex("[A-Za-z0-9_-]{1,80}")
        fun fromJson(id: String, value: JSONObject): LanFile {
            val name = value.getString("fileName")
            val size = value.getLong("size")
            val times = value.getJSONObject("metadata")
            require(identifier.matches(id) && value.getString("id") == id && validName(name))
            require(size in 0..Long.MAX_VALUE - LAN_DIGEST_BYTES)
            val modified = times.getString("modified")
            val accessed = times.optString("accessed").takeUnless { it.isBlank() || it == "null" }
            require(FileTimes.valid(modified) && (accessed == null || FileTimes.valid(accessed)))
            return LanFile(id, name, size, FileTimes(modified, accessed),
                value.optString("fileType", "application/octet-stream"))
        }

        fun validName(name: String) = FileManagerKernel.validName(name) &&
            name.toByteArray(Charsets.UTF_8).size <= 240 && name.none { it.isISOControl() }
    }
}

internal data class FileTimes(val modified: String, val accessed: String?) {
    /** 1 = modification time differs, 2 = access time differs. */
    fun restore(file: File): Int {
        val modification = checkNotNull(parse(modified))
        val access = accessed?.let { checkNotNull(parse(it)) }
        val path = file.path.toByteArray(Charsets.UTF_8)
        var error = NativeFileTimes.write(path, modification.seconds, modification.nanos,
            access?.seconds ?: 0, access?.nanos ?: -1)
        if (error != 0 && access != null) {
            error = NativeFileTimes.write(path, modification.seconds, modification.nanos, 0, -1)
        }
        if (error != 0 && modification.seconds >= 0) {
            file.setLastModified(modification.seconds * 1000 + modification.nanos / 1_000_000)
        }
        val actual = NativeFileTimes.read(path)
        val mtime = actual != null && actual[0] == modification.seconds && actual[1] == modification.nanos
        val atime = access == null || (actual != null && actual[2] == access.seconds && actual[3] == access.nanos)
        return (if (mtime) 0 else 1) or (if (atime) 0 else 2)
    }

    companion object {
        fun read(file: File): FileTimes {
            val value = NativeFileTimes.read(file.path.toByteArray(Charsets.UTF_8))
                ?: throw IOException("无法读取文件时间：${file.name}")
            return FileTimes(format(value[0], value[1]), format(value[2], value[3]))
        }

        fun valid(value: String) = parse(value) != null

        private data class Timestamp(val seconds: Long, val nanos: Long)
        private val wireTimestamp = Regex(
            """(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})"""
        )

        private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private fun format(seconds: Long, nanos: Long): String {
            val whole = utcFormat("yyyy-MM-dd'T'HH:mm:ss").format(Date(seconds * 1000))
            val fraction = if (nanos == 0L) "" else "." + nanos.toString().padStart(9, '0').trimEnd('0')
            return "$whole${fraction}Z"
        }

        private fun parse(value: String): Timestamp? {
            val match = wireTimestamp.matchEntire(value) ?: return null
            val (seconds, fraction, offset) = match.destructured
            val whole = seconds + offset
            val position = ParsePosition(0)
            val date = utcFormat("yyyy-MM-dd'T'HH:mm:ssXXX").parse(whole, position) ?: return null
            if (position.index != whole.length) return null
            return Timestamp(date.time / 1000, fraction.padEnd(9, '0').toLong())
        }
    }
}

@Keep
private object NativeFileTimes {
    init { System.loadLibrary("zipdoctor_times") }
    external fun read(path: ByteArray): LongArray?
    external fun write(path: ByteArray, modifiedSeconds: Long, modifiedNanos: Long,
                       accessedSeconds: Long, accessedNanos: Long): Int
}

internal class LanSource private constructor(
    val file: File,
    val meta: LanFile,
    val directory: Boolean,
    private val stat: StructStat
) {
    fun check() {
        val current = Os.lstat(file.path)
        if (!matches(current) || FileTimes.read(file).modified != meta.times.modified) changed()
    }

    fun writePayload(output: OutputStream, check: () -> Unit, progress: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        try {
            if (!matches(Os.fstat(descriptor))) changed()
            FileInputStream(descriptor).use { input ->
                streamBytes(input, output, meta.size, check, progress, digest)
                if (input.read() >= 0) changed()
            }
        } finally {
            if (descriptor.valid()) Os.close(descriptor)
        }
        this.check()
        output.write(digest.digest())
    }

    private fun matches(current: StructStat): Boolean {
        val sameChangeTime = if (Build.VERSION.SDK_INT >= 27) stat.st_ctim == current.st_ctim
        else stat.st_ctime == current.st_ctime
        val expectedType = if (directory) OsConstants.S_ISDIR(current.st_mode) else OsConstants.S_ISREG(current.st_mode)
        return sameInode(stat, current) && expectedType && (directory || current.st_size == meta.size) && sameChangeTime
    }

    private fun changed(): Nothing = throw IOException("源项目已变化：" + file.name)

    companion object {
        fun inspect(file: File, allowDirectory: Boolean = false): LanSource {
            val stat = Os.lstat(file.path)
            val directory = OsConstants.S_ISDIR(stat.st_mode)
            require((OsConstants.S_ISREG(stat.st_mode) || (allowDirectory && directory)) && LanFile.validName(file.name)) {
                "无法发送文件或文件夹：" + file.name
            }
            val times = FileTimes.read(file)
            val mime = if (directory) "inode/directory" else
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
                    ?: "application/octet-stream"
            return LanSource(file, LanFile(UUID.randomUUID().toString(), file.name,
                if (directory) 0L else stat.st_size, times, mime), directory, stat)
        }
    }
}

internal data class LanReceipt(val name: String, val timeWarnings: Int)
internal class HashMismatch : IOException("文件 SHA-256 校验失败")
internal const val LAN_DIGEST_BYTES = 32

internal object LanDestination {
    fun requireDirectory(folder: File) {
        require(folder.isDirectory && folder.canonicalFile == folder) { "接收目录已移动或不可用" }
    }

    fun createDirectory(parent: File, name: String, renameOnConflict: Boolean): File {
        requireDirectory(parent)
        require(LanFile.validName(name)) { "文件夹名称无效" }
        for (index in 0..(if (renameOnConflict) 9999 else 0)) {
            val target = File(parent, if (index == 0) name else "$name ($index)")
            if (target.mkdir()) return target
            if (!target.exists()) throw IOException("无法创建文件夹：" + target.name)
        }
        throw IOException("目标文件夹名称冲突：" + name)
    }

    fun save(
        folder: File,
        meta: LanFile,
        input: InputStream,
        check: () -> Unit,
        progress: (Long) -> Unit,
        renameOnConflict: Boolean = true
    ): LanReceipt {
        requireDirectory(folder)
        require(LanFile.validName(meta.name)) { "文件名无效" }
        val staging = File.createTempFile(".filedoctor-lan-", ".part", folder)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            staging.outputStream().use { output ->
                streamBytes(input, output, meta.size, check, progress, digest)
                val expected = ByteArray(LAN_DIGEST_BYTES)
                DataInputStream(input).readFully(expected)
                if (!MessageDigest.isEqual(expected, digest.digest())) throw HashMismatch()
                output.fd.sync()
            }
            val warnings = meta.times.restore(staging)
            check()
            requireDirectory(folder)
            val target = publish(staging, folder, meta.name, renameOnConflict)
            // Publishing can pass through the system's shared-storage layer. Verify the final path.
            val actual = runCatching { meta.times.restore(target) }.getOrDefault(warnings or 3)
            return LanReceipt(target.name, actual)
        } finally {
            staging.delete()
        }
    }

    private fun publish(staging: File, folder: File, name: String, renameOnConflict: Boolean): File {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        for (index in 0..(if (renameOnConflict) 9999 else 0)) {
            val target = File(folder, if (index == 0) name else "$stem ($index)$suffix")
            if (!target.createNewFile()) continue
            val reserved = Os.lstat(target.path)
            var published = false
            try {
                val current = Os.lstat(target.path)
                if (!sameEntry(reserved, current) || current.st_size != 0L) throw IOException("目标文件已变化")
                Os.rename(staging.path, target.path)
                published = true
                return target
            } finally {
                if (!published) runCatching {
                    val current = Os.lstat(target.path)
                    if (sameEntry(reserved, current) && current.st_size == 0L) target.delete()
                }
            }
        }
        throw IOException("目标文件名称冲突：" + name)
    }
}

internal fun sameInode(before: StructStat, after: StructStat) =
    before.st_dev == after.st_dev && before.st_ino == after.st_ino

private fun sameEntry(before: StructStat, after: StructStat) =
    sameInode(before, after) && OsConstants.S_ISREG(after.st_mode)

internal fun streamBytes(input: InputStream, output: OutputStream, size: Long,
                         check: () -> Unit, progress: (Long) -> Unit, digest: MessageDigest? = null) {
    val buffer = ByteArray(minOf(256L * 1024, size.coerceAtLeast(1)).toInt())
    var sent = 0L
    while (sent < size) {
        check()
        val count = input.read(buffer, 0, minOf(size - sent, buffer.size.toLong()).toInt())
        if (count < 0) throw EOFException("连接中断，文件未传完")
        output.write(buffer, 0, count)
        digest?.update(buffer, 0, count)
        sent += count
        progress(sent)
    }
}
