package com.wirelessalien.zipxtract.lan

import android.os.SystemClock

internal data class LanProgress(val text: String, val percent: Int? = null)

internal data class LanTask(
    val id: String,
    val title: String,
    val progress: LanProgress,
    val active: Boolean = true,
    val cancelling: Boolean = false
) {
    fun finish(result: LanBatchResult) = copy(
        progress = LanProgress(result.message(), if (result.error == null) 100 else null),
        active = false,
        cancelling = false
    )
}

internal class LanBatchProgress(
    private val total: Long,
    private val totalBytes: Long,
    private val publish: (LanProgress) -> Unit
) {
    private var completed = 0L
    private var completedBytes = 0L
    private var lastUpdate = -150L

    fun payload(name: String): (Long) -> Unit {
        report(name)
        return { bytes -> report(name, bytes) }
    }

    fun complete(name: String, size: Long = 0) {
        completed++
        completedBytes += size
        report(name)
    }

    private fun report(name: String, currentBytes: Long = 0) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdate < 150) return
        lastUpdate = now
        val fraction = if (totalBytes > 0) (completedBytes + currentBytes).toDouble() / totalBytes
                       else completed.toDouble() / total
        val percent = (fraction * 100).toInt().coerceIn(0, 99)
        publish(LanProgress("已处理 $completed/$total 项 · $name", percent))
    }
}
