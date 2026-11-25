package com.example.clmplayer

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Speech time stretching stays in the app; the platform output always runs at normal rate. */
@UnstableApi
internal class SpeechAudioChain : AudioProcessorChain {
    private val sonic = SpeechTimeStretchProcessor()
    @Volatile var speed = 1f
        private set
    @Volatile var inputBytes = 0L
        private set
    @Volatile var outputBytes = 0L
        private set
    private val processor = object : AudioProcessor {
        override fun configure(format: AudioProcessor.AudioFormat) = sonic.configure(format)
        override fun isActive() = sonic.isActive
        override fun queueInput(input: ByteBuffer) {
            val before = input.remaining()
            sonic.queueInput(input)
            inputBytes += before - input.remaining()
        }
        override fun queueEndOfStream() = sonic.queueEndOfStream()
        override fun getOutput(): ByteBuffer = sonic.output.also { outputBytes += it.remaining() }
        override fun isEnded() = sonic.isEnded
        override fun flush() = flush(AudioProcessor.StreamMetadata.DEFAULT)
        override fun flush(metadata: AudioProcessor.StreamMetadata) { sonic.flush(metadata); inputBytes = 0; outputBytes = 0 }
        override fun getDurationAfterProcessorApplied(durationUs: Long) = sonic.getDurationAfterProcessorApplied(durationUs)
        override fun reset() { sonic.reset(); inputBytes = 0; outputBytes = 0; speed = 1f }
    }
    override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(processor)
    override fun applyPlaybackParameters(parameters: PlaybackParameters): PlaybackParameters {
        speed = parameters.speed.coerceIn(0.5f, 2f)
        sonic.setSpeed(speed)
        return PlaybackParameters(speed, 1f)
    }
    // Do not drop quiet syllables, breaths or dramatic pauses in spoken audio.
    override fun applySkipSilenceEnabled(enabled: Boolean) = false
    override fun getMediaDuration(playoutDuration: Long) = sonic.getMediaDuration(playoutDuration)
    override fun getSkippedOutputFrameCount() = 0L
}
