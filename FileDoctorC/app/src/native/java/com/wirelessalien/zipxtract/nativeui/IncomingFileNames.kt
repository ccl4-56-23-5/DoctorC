package com.wirelessalien.zipxtract.nativeui

import java.io.File

/** Names supplied by a content provider are metadata, not URL-encoded strings. */
object IncomingFileNames {
    private const val DEFAULT_NAME = "共享文件"
    private const val MAX_UTF8_BYTES = 240

    /** [decodedLastSegment] must be Uri.lastPathSegment, which Android already decodes once. */
    fun resolve(displayName: String?, decodedLastSegment: String?): String =
        leaf(displayName) ?: leaf(decodedLastSegment) ?: DEFAULT_NAME

    private fun leaf(value: String?): String? {
        if (value == null) return null
        val name = value.substringAfterLast('/').substringAfterLast('\\').filterNot {
            it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' ||
                it == '\u200e' || it == '\u200f'
        }
        if (name.isBlank() || name == "." || name == "..") return null
        // Bound untrusted metadata without cutting a surrogate pair / emoji in half.
        var end = 0
        var bytes = 0
        while (end < name.length) {
            val codePoint = name.codePointAt(end)
            val count = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (bytes + count > MAX_UTF8_BYTES) break
            bytes += count
            end += Character.charCount(codePoint)
        }
        return name.substring(0, end).takeUnless { it.isBlank() || it == "." || it == ".." }
    }

    /** Only the private import copy gets a safe leaf name; never rename the source or an entry. */
    fun destination(folder: File, displayName: String?, decodedLastSegment: String?): File {
        val root = folder.canonicalFile
        return File(root, resolve(displayName, decodedLastSegment)).canonicalFile.also {
            require(it.parentFile == root) { "分享文件名超出导入目录" }
        }
    }
}
