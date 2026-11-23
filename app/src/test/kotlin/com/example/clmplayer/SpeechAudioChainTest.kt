package com.example.clmplayer

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos

/** Signal-level checks: duration is shortened while pitch and stereo remain intact. */
class SpeechAudioChainTest {
    private val rate = 48000
    private fun process(chain: SpeechAudioChain, speed: Float, channels: Int): ShortArray {
        val applied = chain.applyPlaybackParameters(PlaybackParameters(speed, 2f))
        assertEquals(1f, applied.pitch, 0f)
        val p = chain.audioProcessors.single()
        p.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        p.flush()
        val out = ByteArrayOutputStream()
        fun drain() {
            val data = p.output
            val bytes = ByteArray(data.remaining()); data.get(bytes); out.write(bytes)
        }
        var frame = 0
        while (frame < rate * 4) {
            val count = minOf(257, rate * 4 - frame)
            val block = ByteBuffer.allocateDirect(count * channels * 2).order(ByteOrder.nativeOrder())
            repeat(count) { j -> repeat(channels) { ch ->
                val frequency = if (channels == 1) 240 else if (ch == 0) 180 else 360
                val amplitude = if (ch == 0) 8000 else 3000
                block.putShort((amplitude * sin(2 * Math.PI * frequency * (frame + j) / rate)).toInt().toShort())
            } }
            block.flip(); p.queueInput(block); drain(); frame += count
        }
        p.queueEndOfStream(); drain()
        assertTrue(p.isEnded)
        val bytes = out.toByteArray()
        assertEquals(rate * 4.0 / speed, bytes.size / (channels * 2.0), rate * 0.02)
        assertTrue(chain.inputBytes > 0 && chain.outputBytes > 0)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(data.remaining()).also { data.get(it) }
    }
    private fun frequency(data: ShortArray, channels: Int, channel: Int): Double {
        // Crossfades can add tiny zero crossings without moving the tonal pitch.
        // Measure the dominant spectral peak using a Hann-windowed Goertzel bank.
        val frames = data.size / channels
        val count = minOf(16384, frames - 1024)
        val start = (frames-count)/2
        val window = DoubleArray(count) { i -> data[(start+i)*channels+channel] * (0.5-0.5*cos(2*Math.PI*i/(count-1))) }
        var best = 0; var maxPower = -1.0
        for (hz in 80..480) {
            val coefficient = 2*cos(2*Math.PI*hz/rate)
            var previous = 0.0; var older = 0.0
            for (sample in window) { val next = sample + coefficient*previous - older; older=previous; previous=next }
            val power = previous*previous + older*older - coefficient*previous*older
            if (power>maxPower) { maxPower=power; best=hz }
        }
        println("spectral pitch: channels=$channels channel=$channel dominant=${best}Hz frames=$frames")
        return best.toDouble()
    }
    @Test fun doubleSpeedHalvesDurationWithoutDoublingPitchOrClipping() {
        val result = process(SpeechAudioChain(), 2f, 1)
        assertEquals(240.0, frequency(result,1,0), 2.0)
        assertTrue(result.maxOf { abs(it.toInt()) } <= 8100)
    }
    @Test fun doubleSpeedKeepsStereoChannelsAndTheirPitchSeparate() {
        val result = process(SpeechAudioChain(), 2f, 2)
        assertEquals(180.0, frequency(result,2,0), 2.0)
        assertEquals(360.0, frequency(result,2,1), 2.0)
        assertTrue(result.filterIndexed { i, _ -> i%2==0 }.maxOf { abs(it.toInt()) } > 6000)
        assertTrue(result.filterIndexed { i, _ -> i%2==1 }.maxOf { abs(it.toInt()) } <= 3100)
    }
    @Test fun flushingBetweenSpeedChangesDoesNotLeakOldSamples() {
        val chain = SpeechAudioChain()
        for (speed in listOf(1.25f,2f,0.5f,1.5f)) {
            val result = process(chain,speed,1)
            assertEquals(240.0,frequency(result,1,0),2.0)
            assertFalse(chain.applySkipSilenceEnabled(true))
        }
    }
}
