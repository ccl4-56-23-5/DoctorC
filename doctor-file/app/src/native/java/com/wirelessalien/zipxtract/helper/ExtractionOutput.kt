package com.wirelessalien.zipxtract.helper

import android.system.Os
import android.system.OsConstants
import java.io.*
import java.util.UUID

class ExtractionPolicyException(message: String) : IOException(message)

/** One fresh output per decoder attempt. Only files/directories created by this instance are removed. */
internal class ExtractionOutput(parent: File, private val stem: String, selected: List<String>?,
                                private val check: () -> Unit) : AutoCloseable {
    data class Entry(val name: String, val directory: Boolean, val included: Boolean)
    private data class Owned(val file: File, val device: Long, val inode: Long)
    private val parent = parent.canonicalFile
    val root = File(this.parent, ".ZipDoctorC-extract-${UUID.randomUUID()}")
    private val owned = mutableListOf<Owned>()
    private val directories = mutableListOf<Pair<File, Long>>()
    private val names = mutableSetOf<String>()
    private val wanted = selected?.map { normalize(it, true) }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    private val matched = mutableSetOf<String>()
    var written = 0L; private set
    private var committed = false
    init {
        if (!this.parent.isDirectory || !this.parent.canWrite()) throw IOException("输出目录不可写")
        if (!root.mkdir()) throw IOException("无法创建解压临时目录")
        remember(root)
    }
    private fun remember(file: File) {
        val stat = Os.lstat(file.path); owned += Owned(file, stat.st_dev, stat.st_ino)
    }
    fun entry(name: String, directory: Boolean, link: Boolean = false): Entry {
        check()
        val path = normalize(name, directory)
        if (link) throw ExtractionPolicyException("压缩包包含链接或特殊文件：$name")
        if (path.isNotEmpty() && !names.add(path)) throw ExtractionPolicyException("压缩包包含重复条目：$path")
        val matches = wanted.filter { path == it || path.startsWith("$it/") }
        matched.addAll(matches)
        return Entry(path, directory, path.isNotEmpty() && (wanted.isEmpty() || matches.isNotEmpty()))
    }
    private fun destination(name: String): File {
        val file = File(root, name)
        if (ArchiveSafety.isLink(root) || file.canonicalFile != file.absoluteFile ||
            !file.path.startsWith(root.path + File.separator)) throw ExtractionPolicyException("解压路径已变化：$name")
        return file
    }
    private fun mkdirs(file: File) {
        if (file == root) return
        mkdirs(file.parentFile ?: throw IOException("目录路径无效"))
        if (!file.exists()) { if (!file.mkdir()) throw IOException("无法创建目录：${file.name}"); remember(file) }
        if (!file.isDirectory || ArchiveSafety.isLink(file)) throw ExtractionPolicyException("条目与目录冲突：${file.name}")
    }
    fun directory(entry: Entry, modified: Long) {
        if (!entry.included) return
        val file = destination(entry.name); mkdirs(file); directories += file to modified
    }
    fun file(entry: Entry, modified: Long, size: Long = -1): OutputStream {
        check(); require(entry.included && !entry.directory)
        val file = destination(entry.name); mkdirs(file.parentFile!!)
        val fd = try { Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180) }
            catch (e: Exception) { throw IOException("无法创建文件，可能存在同名条目：${entry.name}", e) }
        remember(file)
        val output = FileOutputStream(fd)
        return object : FilterOutputStream(output) {
            var count = 0L; var closed = false
            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                check()
                if (len > 0 && root.usableSpace < len + 1024L * 1024) throw IOException("空间不足，解压已停止")
                out.write(b, off, len); count = Math.addExact(count, len.toLong()); written += len
            }
            override fun close() {
                if (closed) return
                closed = true; super.close()
                if (modified > 0) file.setLastModified(modified)
                if (size >= 0 && count != size) throw IOException("解压长度不匹配：${entry.name}")
            }
        }
    }
    fun finish(): File {
        check()
        if (matched != wanted) throw IOException("所选条目已不存在或名称不匹配")
        val inventory = owned.associateBy { it.file.path }
        fun verify(file: File) {
            check()
            val item = inventory[file.path] ?: throw ExtractionPolicyException("临时目录出现外来文件")
            val stat = Os.lstat(file.path)
            if (stat.st_dev != item.device || stat.st_ino != item.inode || ArchiveSafety.isLink(file))
                throw ExtractionPolicyException("临时解压内容被外部修改")
            if (file.isDirectory) (file.listFiles() ?: throw IOException("目录不可读取")).forEach(::verify)
        }
        verify(root)
        directories.sortedByDescending { it.first.path.length }.forEach { (file, time) -> if (time > 0) file.setLastModified(time) }
        var index = 0
        while (true) {
            check()
            val target = File(parent, stem + if (index == 0) "" else " ($index)")
            index++
            // Reserving the final directory is exclusive and never overwrites an existing item.
            if (!target.mkdir()) { if (target.exists()) continue; throw IOException("无法保存解压目录") }
            val moved = mutableListOf<File>()
            try {
                for (child in root.listFiles() ?: throw IOException("临时目录不可读取")) {
                    check()
                    val destination = File(target, child.name)
                    if (destination.exists() || !child.renameTo(destination)) throw IOException("无法保存解压文件：${child.name}")
                    moved += destination
                }
                root.delete(); committed = true; return target
            } catch (e: Exception) {
                // Move our own completed children back. Never recursively delete a final directory.
                for (file in moved.asReversed()) file.renameTo(File(root, file.name))
                target.delete(); throw e
            }
        }
    }
    override fun close() {
        if (committed) return
        owned.asReversed().forEach { item ->
            try {
                val now = Os.lstat(item.file.path)
                if (now.st_dev == item.device && now.st_ino == item.inode && !ArchiveSafety.isLink(item.file)) item.file.delete()
            } catch (_: Exception) { /* Missing or replaced paths are not ours to delete. */ }
        }
    }
    companion object {
        fun normalize(value: String, directory: Boolean = false): String {
            val raw = value.replace('\\', '/')
            if (raw.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(raw) || '\u0000' in raw)
                throw ExtractionPolicyException("不安全的解压路径：$value")
            val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." } || (!directory && parts.isEmpty())) throw ExtractionPolicyException("不安全的解压路径：$value")
            return parts.joinToString("/")
        }
    }
}
