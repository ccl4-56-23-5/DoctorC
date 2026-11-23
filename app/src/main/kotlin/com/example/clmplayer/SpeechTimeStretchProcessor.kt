package com.example.clmplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Streaming WSOLA: align overlapping waveforms before crossfading. All channels share one
 * alignment, preserving stereo. Speed changes alter the analysis hop, never the sample rate.
 * Uses bounded rolling PCM buffers; does not create files or run Android's speed effects.
 */
@UnstableApi
internal class SpeechTimeStretchProcessor : AudioProcessor {
    private var format = AudioProcessor.AudioFormat.NOT_SET
    private var pendingFormat = AudioProcessor.AudioFormat.NOT_SET
    private var speed = 1f
    private var channels = 1
    private var overlap = 1
    private var hop = 2
    private var searchHalf = 1
    private var fade = DoubleArray(0)
    private var input = ShortArray(0)
    private var inputFrames = 0
    private var origin = 0L
    private var receivedFrames = 0L
    private var nominal = 0.0
    private var tail = ShortArray(0)
    private var hasTail = false
    private var emittedFrames = 0L
    private var generated = ShortArray(0)
    private var generatedCount = 0
    private var ended = false
    private var reusable = AudioProcessor.EMPTY_BUFFER
    private var pending = AudioProcessor.EMPTY_BUFFER

    fun setSpeed(value: Float) { require(value in 0.5f..2f); speed = value }
    fun getMediaDuration(playoutDuration: Long) = (playoutDuration * speed.toDouble()).roundToLong()
    override fun getDurationAfterProcessorApplied(durationUs: Long) = (durationUs / speed.toDouble()).roundToLong()
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        // A new format is negotiated before the old stream is necessarily drained.
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }
    private fun applyFormat() {
        format = pendingFormat
        if (format == AudioProcessor.AudioFormat.NOT_SET) return
        channels = format.channelCount
        overlap = maxOf(8, (format.sampleRate * 0.020).roundToInt())
        hop = overlap * 2
        searchHalf = maxOf(4, (format.sampleRate * 0.012).roundToInt())
        fade = DoubleArray(overlap) { 0.5 - 0.5 * cos(Math.PI * it / (overlap - 1)) }
        tail = ShortArray(overlap * channels)
    }
    override fun isActive() = pendingFormat != AudioProcessor.AudioFormat.NOT_SET && abs(speed - 1f) > 0.0001f

    override fun queueInput(buffer: ByteBuffer) {
        val count = buffer.remaining() / (channels * 2)
        ensureInput((inputFrames + count) * channels)
        buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(input, inputFrames * channels, count * channels)
        buffer.position(buffer.position() + count * channels * 2)
        inputFrames += count; receivedFrames += count
        process()
    }
    override fun queueEndOfStream() { ended = true; process() }
    override fun getOutput(): ByteBuffer = pending.also { pending = AudioProcessor.EMPTY_BUFFER }
    override fun isEnded() = ended && !pending.hasRemaining()
    override fun flush() = flush(AudioProcessor.StreamMetadata.DEFAULT)
    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        applyFormat()
        inputFrames = 0; origin = 0; receivedFrames = 0; nominal = 0.0
        hasTail = false; emittedFrames = 0; generatedCount = 0; ended = false
        pending = AudioProcessor.EMPTY_BUFFER
    }
    override fun reset() {
        flush(); format = AudioProcessor.AudioFormat.NOT_SET; pendingFormat = AudioProcessor.AudioFormat.NOT_SET; speed = 1f
        input = ShortArray(0); generated = ShortArray(0); tail = ShortArray(0)
        fade = DoubleArray(0); reusable = AudioProcessor.EMPTY_BUFFER
    }
    private fun ensureInput(samples: Int) {
        if (input.size < samples) input = input.copyOf(maxOf(samples, input.size * 2, 4096))
    }
    private fun sample(frame: Long, channel: Int): Int {
        val index = frame - origin
        return if (index < 0 || index >= inputFrames) 0 else input[index.toInt() * channels + channel].toInt()
    }
    private fun process() {
        generatedCount = 0
        val target = if (ended) (receivedFrames / speed.toDouble()).roundToLong() else Long.MAX_VALUE
        while (emittedFrames < target) {
            val center = nominal.roundToLong()
            if (!ended && center + searchHalf + hop + overlap > receivedFrames) break
            val selected = if (hasTail) bestMatch(center) else 0L
            val frames = minOf(hop.toLong(), target - emittedFrames).toInt()
            val needed = generatedCount + frames * channels
            if (generated.size < needed) generated = generated.copyOf(maxOf(needed, generated.size * 2, 4096))
            for (i in 0 until frames) for (channel in 0 until channels) {
                val current = sample(selected + i, channel)
                val value = if (hasTail && i < overlap) {
                    tail[i * channels + channel] * (1.0 - fade[i]) + current * fade[i]
                } else current.toDouble()
                generated[generatedCount++] = value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            for (i in 0 until overlap) for (channel in 0 until channels) {
                tail[i * channels + channel] = sample(selected + hop + i, channel).toShort()
            }
            hasTail = true
            emittedFrames += frames
            nominal += hop * speed.toDouble()
            // The next correlation search only needs the future window; its reference is in tail.
            val discard = (nominal.toLong() - searchHalf - 2 - origin).coerceIn(0L, inputFrames.toLong()).toInt()
            if (discard > 0) {
                input.copyInto(input, 0, discard * channels, inputFrames * channels)
                inputFrames -= discard; origin += discard
            }
        }
        if (generatedCount > 0) {
            val bytes = pending.remaining() + generatedCount * 2
            val output = if (pending.hasRemaining() || reusable.capacity() < bytes) {
                ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { reusable = it }
            } else reusable.apply { clear() }
            if (pending.hasRemaining()) output.put(pending)
            val position = output.position()
            output.asShortBuffer().put(generated, 0, generatedCount)
            output.position(position + generatedCount * 2)
            output.flip(); pending = output
        }
    }
    private fun bestMatch(center: Long): Long {
        val low = maxOf(origin, center - searchHalf)
        val high = maxOf(low, if (ended) center + searchHalf else minOf(center + searchHalf, receivedFrames - hop - overlap))
        val nearest = center.coerceIn(low, high)
        var referenceEnergy = 0.0
        for (i in 0 until overlap step 4) for (channel in 0 until channels) {
            val v = tail[i * channels + channel].toDouble(); referenceEnergy += v * v
        }
        if (referenceEnergy < 1.0) return nearest
        fun score(candidate: Long): Double {
            var dot = 0.0; var energy = 0.0
            for (i in 0 until overlap step 4) for (channel in 0 until channels) {
                val a = tail[i * channels + channel].toDouble()
                val b = sample(candidate + i, channel).toDouble()
                dot += a * b; energy += b * b
            }
            return dot / sqrt(referenceEnergy * energy + 1.0) - 0.002 * abs(candidate - center) / searchHalf
        }
        var best = nearest; var bestScore = score(best)
        var candidate = low
        while (candidate <= high) {
            val value = score(candidate)
            if (value > bestScore) { bestScore = value; best = candidate }
            candidate += 4
        }
        val coarse = best
        candidate = maxOf(low, coarse - 4)
        while (candidate <= minOf(high, coarse + 4)) {
            val value = score(candidate)
            if (value > bestScore) { bestScore = value; best = candidate }
            candidate++
        }
        return best
    }
}
