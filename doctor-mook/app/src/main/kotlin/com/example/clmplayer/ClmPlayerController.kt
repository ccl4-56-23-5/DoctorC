package com.example.clmplayer

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory

@UnstableApi
internal class ClmPlayerController(private val context: Context) {
    private var player: ExoPlayer? = null
    private var chain: SpeechAudioChain? = null
    private var prepared = false
    private var speed = 1f
    var onPrepared: ((Long, Long) -> Unit)? = null
    var onCompleted: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPlayState: ((Boolean) -> Unit)? = null
    var onPlaybackIntentChanged: (() -> Unit)? = null
    var onLoading: ((Boolean) -> Unit)? = null

    data class AudioDiagnostics(val speed: Float, val pitch: Float, val inputBytes: Long, val outputBytes: Long)
    fun audioDiagnostics(): AudioDiagnostics? = player?.let { media -> chain?.let {
        AudioDiagnostics(media.playbackParameters.speed, media.playbackParameters.pitch, it.inputBytes, it.outputBytes)
    } }

    fun play(archive: ClmArchive, track: ClmTrack, resumeMs: Long?, playbackSpeed: Float, introMs: Long, outroMs: Long) {
        stop()
        speed = playbackSpeed.coerceIn(0.5f, 2f)
        val speech = SpeechAudioChain()
        chain = speech
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(false).setAudioProcessorChain(speech).build()
        }.setEnableAudioOutputPlaybackParameters(false).setEnableAudioFloatOutput(false)
        val media = ExoPlayer.Builder(context, renderers)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000, 15000, 250, 750).build()).build()
        player = media
        media.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== media) return
                // Seeking must not disable the slider halfway through a user's drag.
                onLoading?.invoke(state == Player.STATE_BUFFERING && !prepared)
                if (state == Player.STATE_READY && !prepared) {
                    prepared = true
                    val duration = media.duration.coerceAtLeast(0L)
                    val window = PlaybackWindow.create(duration, introMs, outroMs)
                    val startAt = window.startAt(resumeMs)
                    if (window.reachesOutro(startAt) && media.playWhenReady) { finish(media); return }
                    media.seekTo(startAt)
                    onPrepared?.invoke(duration, startAt)
                }
                if (state == Player.STATE_ENDED && media.playWhenReady) finish(media)
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (player === media) onPlaybackIntentChanged?.invoke()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player === media) onPlayState?.invoke(isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== media) return
                stop(); onError?.invoke("音频无法播放（${error.errorCodeName}）")
            }
        })
        try {
            media.setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            media.setHandleAudioBecomingNoisy(true)
            media.setWakeMode(C.WAKE_MODE_LOCAL)
            media.setSeekParameters(SeekParameters.EXACT)
            media.trackSelectionParameters = media.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder().setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
            media.playbackParameters = PlaybackParameters(speed, 1f)
            media.skipSilenceEnabled = false
            val item = MediaItem.Builder().setUri(Uri.parse("clm://${archive.id}/${track.entry.ordinal}"))
                .setMimeType(track.entry.mime).build()
            val factory = DataSource.Factory { ClmStreamDataSource(archive.container, track.entry) }
            media.setMediaSource(ProgressiveMediaSource.Factory(factory, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)).createMediaSource(item))
            media.playWhenReady = true
            onLoading?.invoke(true)
            media.prepare()
        } catch (exception: Exception) {
            if (player === media) { stop(); onError?.invoke(exception.message ?: "无法打开音频") }
        }
    }
    fun isActive(): Boolean = player != null
    fun isEnded(): Boolean = player?.playbackState == Player.STATE_ENDED
    fun canSeek(): Boolean = player?.isCurrentMediaItemSeekable == true
    fun wantsPlayback(): Boolean = player?.playWhenReady == true
    fun resume() { player?.play() }
    fun pause() { player?.pause() }
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }
    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 2f)
        player?.playbackParameters = PlaybackParameters(speed, 1f)
    }
    fun currentPosition(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
    fun completeForOutro() { player?.let(::finish) }
    fun stop() {
        val media = player
        player = null; prepared = false; chain = null
        media?.release()
        onPlayState?.invoke(false)
        onLoading?.invoke(false)
    }
    fun release() = stop()
    private fun finish(media: ExoPlayer) {
        if (player !== media) return
        stop(); onCompleted?.invoke()
    }
}
