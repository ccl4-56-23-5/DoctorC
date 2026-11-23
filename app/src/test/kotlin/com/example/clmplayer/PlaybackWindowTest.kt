package com.example.clmplayer

import org.junit.Assert.*
import org.junit.Test

class PlaybackWindowTest {
    @Test fun defaultsPlayCompleteTrack() {
        val window = PlaybackWindow.create(100_000, 0, 0)
        assertEquals(0L, window.startAt(null))
        assertFalse(window.reachesOutro(100_000))
    }

    @Test fun freshStartAndOutroThresholdUseSeparateSettings() {
        val window = PlaybackWindow.create(100_000, 15_000, 10_000)
        assertEquals(15_000L, window.startAt(null))
        assertFalse(window.reachesOutro(89_999))
        assertTrue(window.reachesOutro(90_000))
    }

    @Test fun resumeKeepsItsPositionAndStillRespectsOutro() {
        val window = PlaybackWindow.create(100_000, 15_000, 10_000)
        assertEquals(5_000L, window.startAt(5_000))
        assertTrue(window.reachesOutro(window.startAt(95_000)))
        assertEquals(100_000L, window.startAt(120_000))
    }

    @Test fun shortTrackAndOverlappingSkipsDoNotSkipWholeTrack() {
        val window = PlaybackWindow.create(20_000, 12_000, 8_000)
        assertEquals(0L, window.startAt(null))
        assertEquals(3_000L, window.startAt(3_000))
        assertFalse(window.reachesOutro(19_999))
        assertEquals(0L, PlaybackWindow.create(0, 2_000, 2_000).startAt(null))
        assertEquals(0L, PlaybackWindow.create(5_000, Long.MAX_VALUE, 1_000).startAt(null))
    }

    @Test fun skipsMayBeEnabledIndependently() {
        val intro = PlaybackWindow.create(10_000, 1_000, 0)
        assertEquals(1_000L, intro.startAt(null))
        assertFalse(intro.reachesOutro(9_999))
        val outro = PlaybackWindow.create(10_000, 0, 1_000)
        assertEquals(0L, outro.startAt(null))
        assertTrue(outro.reachesOutro(9_000))
    }

    @Test fun completionAdvancesOnlyWhenAnotherTrackExists() {
        assertTrue(nextTrackIndex(0, 3) == 1)
        assertTrue(nextTrackIndex(1, 3) == 2)
        assertNull(nextTrackIndex(2, 3))
        assertNull(nextTrackIndex(0, 0))
        assertNull(nextTrackIndex(-1, 3))
        assertEquals(2_000L, PlaybackWindow.create(10_000, 2_000, 1_000).startAt(null))
    }
}
