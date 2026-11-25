package com.wirelessalien.zipxtract.nativeui

import java.io.File

/** A MediaStore display name, suffix, size, or relative path is never proof of file identity. */
object MediaStoreFileMatch {
    data class Row(val id: Long, val data: String?, val mediaType: Int)

    fun unique(file: File, rows: List<Row>): Row? {
        // Ambiguous / malformed provider results fail closed to FileProvider.
        if (rows.size != 1 || rows[0].id <= 0L) return null
        val data = rows[0].data?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            rows[0].takeIf { File(data).isAbsolute && File(data).canonicalFile == file.canonicalFile }
        } catch (_: Exception) {
            null
        }
    }
}
