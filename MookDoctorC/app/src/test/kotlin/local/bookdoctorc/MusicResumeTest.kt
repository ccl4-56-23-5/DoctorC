package local.bookdoctorc

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicResumeTest {
    @Test fun resumesValidProgress() {
        assertEquals(42_000L, musicResumePosition(42_000, 60_000))
        assertEquals(700L, musicResumePosition(700, 800))
    }

    @Test fun restartsAtTheEndOrWithStaleProgress() {
        assertEquals(0L, musicResumePosition(59_500, 60_000))
        assertEquals(0L, musicResumePosition(60_000, 60_000))
        assertEquals(0L, musicResumePosition(120_000, 60_000))
        assertEquals(0L, musicResumePosition(750, 800))
    }

    @Test fun rejectsPositionsWithoutAKnownDuration() {
        assertEquals(0L, musicResumePosition(30_000, Long.MIN_VALUE + 1))
        assertEquals(0L, musicResumePosition(-1, 60_000))
    }
}
