package com.wirelessalien.zipxtract.nativeui

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File

internal object PhotoTimeSync {
    const val ACTION = "sync_photo_time"
    const val TITLE = "同步照片时间"

    data class Result(
        val updatedPaths: List<String>,
        val matching: Int,
        val skipped: Int,
        val failed: Int,
        val pending: Int,
        val details: List<String>
    ) {
        fun message() = buildString {
            append("已同步 ${updatedPaths.size} 张 · 已一致 $matching 张")
            append("\n跳过 $skipped 项 · 失败 $failed 项")
            if (pending > 0) append("\n已取消，尚有 $pending 项未处理。")
            if (details.isNotEmpty()) append("\n\n").append(details.joinToString("\n"))
            val remaining = skipped + failed - details.size
            if (remaining > 0) append("\n另有 $remaining 项未显示。")
        }
    }

    private enum class Status { UPDATED, MATCHING, SKIPPED, FAILED }
    private data class Outcome(val status: Status, val reason: String = "")

    fun run(files: List<File>, cancelled: () -> Boolean, progress: (Int) -> Unit): Result {
        val updated = mutableListOf<String>()
        val details = mutableListOf<String>()
        var matching = 0
        var skipped = 0
        var failed = 0
        var processed = 0
        var lastProgress = 0
        val selected = files.distinctBy { it.absolutePath }
        for (file in selected) {
            if (cancelled()) break
            val outcome = try {
                sync(file)
            } catch (_: android.system.ErrnoException) {
                Outcome(Status.FAILED, "文件不可访问或已移动")
            } catch (_: SecurityException) {
                Outcome(Status.FAILED, "没有访问权限")
            }
            when (outcome.status) {
                Status.UPDATED -> updated += file.absolutePath
                Status.MATCHING -> matching++
                Status.SKIPPED -> skipped++
                Status.FAILED -> failed++
            }
            if (outcome.reason.isNotEmpty() && details.size < 30) {
                details += "${file.absolutePath}\n${outcome.reason}"
            }
            processed++
            val percent = (processed * 100L / selected.size).toInt()
            if (percent != lastProgress) {
                progress(percent)
                lastProgress = percent
            }
        }
        return Result(updated, matching, skipped, failed, selected.size - processed, details)
    }

    private fun sync(file: File): Outcome {
        val before = Os.lstat(file.path)
        if (!OsConstants.S_ISREG(before.st_mode) || !MediaFacts.isExifPhotoCandidate(file)) {
            return Outcome(Status.SKIPPED, "非照片文件")
        }
        val modified = file.lastModified()
        val capture = MediaFacts.exifCaptureDate(file)?.millis
            ?: return Outcome(Status.SKIPPED, "缺少或无法读取有效的 EXIF 拍摄时间")
        if (capture < 0) return Outcome(Status.SKIPPED, "拍摄时间早于文件修改时间支持的范围")
        val current = Os.lstat(file.path)
        if (!sameFile(before, current) || current.st_size != before.st_size ||
            current.st_ctime != before.st_ctime || file.lastModified() != modified
        ) {
            return Outcome(Status.FAILED, "读取期间文件发生变化")
        }
        if (modified == capture) return Outcome(Status.MATCHING)
        if (!file.setLastModified(capture)) return Outcome(Status.FAILED, "存储位置拒绝更新时间")
        val actual = file.lastModified()
        if (!sameFile(before, Os.lstat(file.path)) || (actual != capture && actual != capture / 1000 * 1000)) {
            return Outcome(Status.FAILED, "回读时间与拍摄时间不符")
        }
        return Outcome(if (actual == modified) Status.MATCHING else Status.UPDATED)
    }

    private fun sameFile(first: StructStat, second: StructStat) =
        first.st_dev == second.st_dev && first.st_ino == second.st_ino && OsConstants.S_ISREG(second.st_mode)
}
