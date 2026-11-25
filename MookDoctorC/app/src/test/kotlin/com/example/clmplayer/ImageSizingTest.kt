package com.example.clmplayer
import org.junit.Assert.*
import org.junit.Test

class ImageSizingTest {
    @Test fun sampleAlwaysStartsAtOne() {
        assertEquals(1, ImageSizing.sampleSize(589, 798))
        assertEquals(1, ImageSizing.sampleSize(0, 0))
        assertEquals(1, ImageSizing.sampleSize(-1, -1))
    }
    @Test fun bigCoverIsReducedByPowersOfTwo() {
        assertEquals(2, ImageSizing.sampleSize(1800, 2400))
        assertEquals(2, ImageSizing.sampleSize(2401, 3200))
        assertEquals(4, ImageSizing.sampleSize(7000, 5500))
    }
    @Test fun exactBoundaryIsNotResampled() {
        assertEquals(1, ImageSizing.sampleSize(1200, 1200))
        assertEquals(1, ImageSizing.sampleSize(1201, 1200))
    }
    @Test fun corruptHugeDimensionsCannotOverflowOrDivideByZero() {
        val n = ImageSizing.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(n > 0)
        assertTrue(Int.MAX_VALUE.toLong() / n >= 1200)
        assertTrue(Int.MAX_VALUE.toLong() / (n * 2L) < 1200)
    }
    @Test fun thumbnailUsesItsOwnBound() {
        assertEquals(4, ImageSizing.sampleSize(1200, 1800, 256))
    }
    @Test fun detailedCoverIsNotDecodedBelowTheDisplaySize() {
        assertEquals(1, ImageSizing.sampleSize(900, 900, 546))
        assertEquals(2, ImageSizing.sampleSize(1092, 1092, 546))
        assertEquals(1, ImageSizing.sampleSize(1091, 1091, 546))
    }
    @Test fun invalidLimitIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ImageSizing.sampleSize(1, 1, 0) }
    }
}
