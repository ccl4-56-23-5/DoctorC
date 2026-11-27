package com.wirelessalien.zipxtract.lan

import android.os.SystemClock
import java.util.Locale

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
    private var speedAt = -1L
    private var speedBytes = 0L
    private var rate = ""

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
        val bytes = completedBytes + currentBytes
        if (speedAt < 0) {
            speedAt = now
            speedBytes = bytes
        } else if (now - speedAt >= 750L) {
            val speed = (bytes - speedBytes).coerceAtLeast(0) * 1000.0 / (now - speedAt) / (1024 * 1024)
            rate = " · " + String.format(Locale.ROOT, "%.1f MiB/s", speed)
            speedAt = now
            speedBytes = bytes
        }
        val fraction = if (totalBytes > 0) bytes.toDouble() / totalBytes
                       else completed.toDouble() / total
        val percent = (fraction * 100).toInt().coerceIn(0, 99)
        publish(LanProgress("已处理 $completed/$total 项$rate · $name", percent))
    }
}
