package com.example.clmplayer

/** A track keeps its full content when the requested skips leave no playable interval. */
internal data class PlaybackWindow(val durationMs: Long, val introMs: Long, val outroStartMs: Long?) {
    fun startAt(resumeMs: Long?): Long = (resumeMs ?: introMs).coerceIn(0L, durationMs)

    fun reachesOutro(positionMs: Long): Boolean =
        outroStartMs?.let { positionMs >= it } ?: false

    companion object {
        fun create(durationMs: Long, requestedIntroMs: Long, requestedOutroMs: Long): PlaybackWindow {
            val duration = durationMs.coerceAtLeast(0L)
            val intro = requestedIntroMs.coerceAtLeast(0L)
            val outro = requestedOutroMs.coerceAtLeast(0L)
            val skipsFit = duration > 0L && intro < duration && outro < duration - intro
            if (!skipsFit) return PlaybackWindow(duration, 0L, null)
            return PlaybackWindow(duration, intro, if (outro > 0L) duration - outro else null)
        }
    }
}

internal fun nextTrackIndex(currentIndex: Int, trackCount: Int): Int? =
    if (currentIndex >= 0 && currentIndex < trackCount - 1) currentIndex + 1 else null
