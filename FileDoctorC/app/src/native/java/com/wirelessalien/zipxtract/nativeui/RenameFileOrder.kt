package com.wirelessalien.zipxtract.nativeui

import java.io.File
import java.util.Locale

/** Sort identities before generating numbered names; all filesystem reads belong on the IO worker. */
object RenameFileOrder {
    enum class Key(val title: String) {
        ORIGINAL("选择顺序"), NATURAL("名称（自然）"), NAME("名称（字母）"),
        CAPTURED("拍摄时间"), MODIFIED("修改时间"), SIZE("文件大小"), EXTENSION("扩展名")
    }
    data class Settings(val key: Key = Key.CAPTURED, val descending: Boolean = false) {
        val title: String get() = "${key.title} · ${if (descending) "降序" else "升序"}"
    }
    private data class Item(val file: File, val index: Int, val time: Long, val size: Long, val extension: String)

    fun sort(files: List<File>, settings: Settings): List<File> {
        if (settings.key == Key.ORIGINAL) return if (settings.descending) files.reversed() else files.toList()
        val items = files.mapIndexed { i, file ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val time = when (settings.key) {
                Key.CAPTURED -> MediaFacts.captureTime(file)
                Key.MODIFIED -> file.lastModified()
                else -> 0L
            }
            Item(file, i, time,
                if (settings.key == Key.SIZE) file.length() else 0,
                if (settings.key == Key.EXTENSION && !file.isDirectory) file.extension.lowercase(Locale.ROOT) else "")
        }
        return items.sortedWith { a, b ->
            val comparison = when (settings.key) {
                Key.NATURAL -> naturalCompare(a.file.name, b.file.name)
                Key.NAME -> a.file.name.compareTo(b.file.name, ignoreCase = true)
                Key.CAPTURED, Key.MODIFIED -> a.time.compareTo(b.time)
                Key.SIZE -> a.size.compareTo(b.size)
                Key.EXTENSION -> a.extension.compareTo(b.extension)
                Key.ORIGINAL -> 0
            }
            // Stable ties preserve the original selection order in either direction.
            if (comparison == 0) a.index.compareTo(b.index)
            else if (settings.descending) -comparison.sign() else comparison.sign()
        }.map { it.file }
    }
    private fun Int.sign() = compareTo(0)

    /** Arbitrarily long ASCII digit runs, no integer parsing or locale-dependent collation. */
    fun naturalCompare(left: String, right: String): Int {
        val a = left.lowercase(Locale.ROOT); val b = right.lowercase(Locale.ROOT)
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] in '0'..'9' && b[j] in '0'..'9') {
                var ae = i; var be = j
                while (ae < a.length && a[ae] in '0'..'9') ae++
                while (be < b.length && b[be] in '0'..'9') be++
                var az = i; var bz = j
                while (az < ae - 1 && a[az] == '0') az++
                while (bz < be - 1 && b[bz] == '0') bz++
                val length = (ae - az).compareTo(be - bz)
                if (length != 0) return length
                for (n in 0 until ae - az) {
                    val digit = a[az + n].compareTo(b[bz + n])
                    if (digit != 0) return digit
                }
                i = ae; j = be
            } else {
                val comparison = a[i].compareTo(b[j])
                if (comparison != 0) return comparison
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
