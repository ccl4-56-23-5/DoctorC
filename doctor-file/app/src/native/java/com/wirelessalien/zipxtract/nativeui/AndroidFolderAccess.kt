package com.wirelessalien.zipxtract.nativeui

import android.os.Environment
import java.io.File

/** Resolve just the two well-known shared-storage roots. Keep their display paths conventional. */
internal object AndroidFolderAccess {
    private const val COMPAT_SUFFIX = "\u200B"
    private val names = listOf("data", "obb")

    private fun root() = File(Environment.getExternalStorageDirectory(), "Android")

    fun resolve(folder: File): File {
        if (folder.parentFile != root() || folder.name !in names) return folder
        val ordinary = listNames(folder)
        val alternate = File(folder.parentFile, folder.name + COMPAT_SUFFIX)
        val available = listNames(alternate) ?: return folder
        // Prefer the ordinary path whenever it already exposes the same entries.
        return if (ordinary == null || available.any { it !in ordinary }) alternate else folder
    }

    fun displayFile(file: File): File {
        for (name in names) {
            val ordinary = File(root(), name).path
            val alternate = ordinary + COMPAT_SUFFIX
            if (file.path == alternate || file.path.startsWith("$alternate/")) {
                return File(ordinary + file.path.removePrefix(alternate))
            }
        }
        return file
    }

    private fun listNames(folder: File): Set<String>? =
        try { folder.list()?.toHashSet() } catch (_: SecurityException) { null }
}
