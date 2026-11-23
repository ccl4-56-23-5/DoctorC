/* Copyright (C) 2026. GPL-3.0-or-later; see License. */
package com.wirelessalien.zipxtract.helper

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** A closed inventory: no following links, silent unreadable directories, or recursive deletion. */
class ArchiveSafety private constructor(val roots: List<File>, val base: File, val entries: List<Entry>) {
    val entriesByName: Map<String, Entry> = entries.associateBy { it.name }
    data class Entry(val file: File, val name: String, val directory: Boolean, val size: Long,
                     val modified: Long, val hash: String)

    fun assertOutputOutsideSources(output: File) {
        val target = output.canonicalFile
        if (roots.any { target == it || (it.isDirectory && target.path.startsWith(it.path + File.separator)) })
            throw IOException("输出位置不能位于待压缩目录内 / Output is inside the source")
    }

    fun assertUnchanged(check: () -> Unit) {
        val fresh = capture(roots.map { it.path }, check)
        if (fresh.entries.map { it.copy(file = File(it.name)) } != entries.map { it.copy(file = File(it.name)) })
            throw IOException("源文件已变化，已保留全部源文件 / Source changed; originals retained")
    }

    /** Deletes only inventory leaves after a full check. New children prevent removal of their directory. */
    fun deleteVerifiedSources(check: () -> Unit): List<String> = deleteVerifiedSources(check, false)
    fun deleteVerifiedSources(check: () -> Unit, retainingOnInterruption: Boolean): List<String> {
        fun presentOrUnknown(file: File): Boolean = file.exists() ||
            (file.parentFile?.list()?.let { file.name in it } ?: true)
        try { assertUnchanged(check) } catch (e: Exception) {
            if (!retainingOnInterruption) throw e
            return entries.filter { presentOrUnknown(it.file) }.map { it.name }
        }
        val retained = mutableListOf<String>()
        val ordered = entries.sortedByDescending { it.file.path.length }
        for ((index, entry) in ordered.withIndex()) {
            try {
            check()
            val file = entry.file
            val same = file.canonicalFile == file.absoluteFile && !isLink(file) && file.exists() && file.isDirectory == entry.directory &&
                (entry.directory || (file.length() == entry.size && file.lastModified() == entry.modified &&
                    file.inputStream().use { digest(it, check) } == entry.hash))
            if (!same || !file.delete()) retained += entry.name
            } catch (e: Exception) {
                if (!retainingOnInterruption) throw e
                retained += ordered.drop(index).filter { presentOrUnknown(it.file) }.map { it.name }
                break
            }
        }
        return retained
    }

    companion object {
        fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw CancellationException("操作已取消；源文件保留") }
        fun isLink(file: File): Boolean = file.canonicalFile != File(file.parentFile?.canonicalFile, file.name)

        fun digest(input: InputStream, check: () -> Unit): String {
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }

        fun capture(paths: List<String>, check: () -> Unit = {}): ArchiveSafety {
            if (paths.isEmpty()) throw IOException("未选择文件 / No sources")
            val selected = paths.map { File(it).absoluteFile }.distinct().sortedBy { it.path.length }
            val roots = mutableListOf<File>()
            for (file in selected) {
                check()
                if (isLink(file)) throw IOException("不支持压缩符号链接 / Symbolic link: ${file.name}")
                val canonical = file.canonicalFile
                if (roots.none { canonical == it || canonical.path.startsWith(it.path + File.separator) }) roots += canonical
            }
            var base = roots.first().parentFile ?: throw IOException("不能压缩根目录")
            while (roots.any { !it.path.startsWith(base.path + File.separator) })
                base = base.parentFile ?: throw IOException("源文件没有共同父目录")
            val entries = mutableListOf<Entry>()
            fun visit(file: File) {
                check()
                if (isLink(file) || !file.exists() || !file.canRead()) throw IOException("文件不可读取或为链接: ${file.name}")
                val directory = file.isDirectory
                if (!directory && !file.isFile) throw IOException("不支持的文件类型: ${file.name}")
                val modified = file.lastModified()
                val size = if (directory) 0L else file.length()
                val hash = if (directory) "" else file.inputStream().use { digest(it, check) }
                if (!directory && (file.length() != size || file.lastModified() != modified)) throw IOException("读取时源文件变化")
                entries += Entry(file, file.relativeTo(base).invariantSeparatorsPath, directory, size, modified, hash)
                if (directory) (file.listFiles() ?: throw IOException("目录不可读取: ${file.name}"))
                    .sortedBy { it.name }.forEach(::visit)
            }
            roots.forEach(::visit)
            return ArchiveSafety(roots, base, entries)
        }
    }
}
