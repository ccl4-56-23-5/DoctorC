package com.example.clmplayer

internal object ImageSizing {
    fun sampleSize(width: Int, height: Int, maxSide: Int = 1200): Int {
        require(maxSide > 0)
        val largest = maxOf(width, height).coerceAtLeast(1)
        var sample = 1
        while (largest / (sample * 2L) >= maxSide) sample *= 2
        return sample
    }
}
