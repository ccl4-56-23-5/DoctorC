package com.wirelessalien.zipxtract.activity

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.wirelessalien.zipxtract.nativeui.dp
import com.wirelessalien.zipxtract.nativeui.label
import kotlinx.coroutines.launch
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class MediaPreviewActivity : ComponentActivity() {
    private lateinit var pager: RecyclerView
    private lateinit var title: TextView
    private lateinit var positionText: TextView
    private lateinit var previous: TextView
    private lateinit var next: TextView
    private lateinit var controls: LinearLayout
    private lateinit var play: TextView
    private lateinit var speed: TextView
    private lateinit var time: TextView
    private lateinit var trackInfo: TextView
    private lateinit var seek: SeekBar
    private val snap = PagerSnapHelper()
    private val layout by lazy { LinearLayoutManager(this, RecyclerView.HORIZONTAL, false) }
    private var items = emptyList<PreviewItem>()
    private var page = RecyclerView.NO_POSITION
    private var started = false
    private var player: ExoPlayer? = null
    private var videoPage: PreviewVideoPage? = null
    private var resumeAt = 0L
    private var resumePlaying = true
    private var rate = 1f
    private var draggingSeek = false
    private val main = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            if (player == null) return
            updateTiming()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resumeAt = savedInstanceState?.getLong("time") ?: 0L
        resumePlaying = savedInstanceState?.getBoolean("playing") ?: true
        rate = savedInstanceState?.getFloat("rate")?.takeIf { it in RATES } ?: 1f
        buildView()
        lifecycleScope.launch {
            val sequence = MediaPreviewIntent.read(this@MediaPreviewActivity, intent)
            items = sequence.items
            if (items.isEmpty()) {
                finish()
                return@launch
            }
            val restored = savedInstanceState?.getInt("page", -1) ?: -1
            page = if (restored in items.indices) restored else sequence.start
            refreshHeading()
            pager.adapter = Pages()
            layout.scrollToPositionWithOffset(page, 0)
            pager.doOnNextLayout { settlePage() }
        }
    }

    private fun buildView() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("‹", "返回文件列表") { finish() }, LinearLayout.LayoutParams(dp(48), dp(52)))
        title = label("", 15f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        positionText = label("", 12f, SECONDARY)
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(positionText)
        }
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        previous = button("‹", "上一个媒体") { turn(-1) }
        next = button("›", "下一个媒体") { turn(1) }
        header.addView(previous, LinearLayout.LayoutParams(dp(48), dp(52)))
        header.addView(next, LinearLayout.LayoutParams(dp(48), dp(52)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        pager = RecyclerView(this).apply {
            layoutManager = layout
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_IDLE) settlePage()
                }
            })
        }
        snap.attachToRecyclerView(pager)
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        controls = buildControls()
        controls.visibility = View.GONE
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun buildControls(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(10))
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        play = button("播放", "播放") { togglePlayback() }.apply { isEnabled = false }
        speed = button(speedLabel(rate), "播放倍速") { selectSpeed() }.apply { isEnabled = false }
        seek = SeekBar(this).apply {
            contentDescription = "播放进度"
            max = 1000
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(view: SeekBar) { draggingSeek = true }
                override fun onStopTrackingTouch(view: SeekBar) {
                    player?.let { if (it.isCurrentMediaItemSeekable) it.seekTo(relativePosition(it, view.progress)) }
                    draggingSeek = false
                    updateTiming()
                }
                override fun onProgressChanged(view: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    player?.let {
                        if (draggingSeek) updateTiming(relativePosition(it, progress))
                        else if (it.isCurrentMediaItemSeekable) it.seekTo(relativePosition(it, progress))
                    }
                }
            })
        }
        row.addView(play, LinearLayout.LayoutParams(dp(54), dp(48)))
        row.addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(speed, LinearLayout.LayoutParams(dp(64), dp(48)))
        bar.addView(row)
        time = label("00:00 / 00:00", 12f, SECONDARY)
        trackInfo = label("", 12f, SECONDARY).apply { gravity = Gravity.END; maxLines = 2 }
        val details = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            addView(time)
            addView(trackInfo, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        }
        bar.addView(details)
        return bar
    }

    private fun button(text: String, description: String, action: () -> Unit) =
        label(text, 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            contentDescription = description
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun turn(delta: Int) {
        if (pager.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val target = page + delta
        if (target in items.indices) pager.smoothScrollToPosition(target)
    }

    private fun refreshHeading() {
        val item = items.getOrNull(page) ?: return
        title.text = item.name
        positionText.text = if (items.size > 1) "${page + 1} / ${items.size}" else ""
        positionText.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        previous.isEnabled = page > 0
        next.isEnabled = page < items.lastIndex
        previous.alpha = if (previous.isEnabled) 1f else .30f
        next.alpha = if (next.isEnabled) 1f else .30f
        if (items.size == 1) {
            previous.visibility = View.GONE
            next.visibility = View.GONE
        }
        controls.visibility = if (previewKind(item.mime) == PreviewKind.VIDEO) View.VISIBLE else View.GONE
    }

    private fun settlePage() {
        if (pager.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
        val view = snap.findSnapView(layout) ?: return
        val index = pager.getChildAdapterPosition(view)
        if (index !in items.indices) return
        if (index != page) {
            releaseVideo()
            page = index
            resumeAt = 0
            resumePlaying = true
            draggingSeek = false
            refreshHeading()
        }
        if (started && view is PreviewVideoPage && view !== videoPage) {
            releaseVideo()
            startVideo(view, items[index])
        }
    }

    private fun startVideo(view: PreviewVideoPage, item: PreviewItem) {
        view.report(null)
        trackInfo.text = ""
        val uri = item.uri(this)
        if (uri == null) {
            view.report("无法读取此视频")
            updateTiming()
            return
        }
        val active = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build()
        player = active
        videoPage = view
        active.setAudioAttributes(AudioAttributes.DEFAULT, true)
        active.setHandleAudioBecomingNoisy(true)
        active.setVideoSurfaceView(view.surface)
        active.setPlaybackParameters(PlaybackParameters(rate))
        active.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (player === active && size.height > 0) view.aspect(size.width * size.pixelWidthHeightRatio / size.height)
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (player !== active) return
                view.report(if (tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                    "这台设备无法解码此视频轨道" else null)
                trackInfo.text = trackLabels(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player === active) view.report("无法播放此视频。请检查文件及设备解码支持。")
            }

            override fun onEvents(changed: Player, events: Player.Events) {
                if (player === changed) {
                    updatePlay()
                    updateTiming()
                }
            }
        })
        active.setMediaItem(MediaItem.fromUri(uri), resumeAt)
        active.playWhenReady = resumePlaying
        active.prepare()
        updatePlay()
        progressTick.run()
    }

    private fun togglePlayback() {
        val active = player ?: return
        if (active.playbackState == Player.STATE_ENDED) {
            active.seekTo(0)
            active.play()
        } else if (active.playWhenReady) active.pause()
        else active.play()
    }

    private fun selectSpeed() {
        val choices = RATES.map(::speedLabel).toTypedArray()
        AlertDialog.Builder(this).setTitle("播放倍速")
            .setSingleChoiceItems(choices, RATES.indexOf(rate)) { dialog, index ->
                rate = RATES[index]
                player?.setPlaybackParameters(PlaybackParameters(rate))
                updatePlay()
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun updatePlay() {
        val active = player
        val playing = active?.playWhenReady == true && active.playbackState != Player.STATE_ENDED
        play.text = if (playing) "暂停" else "播放"
        play.contentDescription = play.text
        play.isEnabled = active != null && active.playerError == null
        speed.isEnabled = active != null
        speed.text = speedLabel(rate)
        speed.contentDescription = "播放倍速 ${speed.text}"
    }

    private fun updateTiming(whileSeeking: Long? = null) {
        val active = player
        val duration = active?.duration?.coerceAtLeast(0) ?: 0L
        val current = active?.currentPosition?.coerceAtLeast(0) ?: 0L
        seek.isEnabled = active?.isCurrentMediaItemSeekable == true && duration > 0
        if (!draggingSeek) seek.progress = if (duration > 0) (1000.0 * current / duration).toInt().coerceIn(0, 1000) else 0
        if (!draggingSeek || whileSeeking != null) {
            val reading = "${clock(whileSeeking ?: current)} / ${clock(duration)}"
            if (time.text != reading) time.text = reading
        }
    }

    private fun relativePosition(player: Player, progress: Int) =
        (player.duration.coerceAtLeast(0) * (progress / 1000.0)).toLong()

    private fun snapshotVideo() {
        player?.let {
            resumeAt = it.currentPosition.coerceAtLeast(0)
            resumePlaying = it.playWhenReady && it.playbackState != Player.STATE_ENDED
        }
    }

    private fun releaseVideo() {
        main.removeCallbacks(progressTick)
        val old = player
        player = null
        videoPage?.let { old?.clearVideoSurfaceView(it.surface) }
        videoPage = null
        old?.release()
        trackInfo.text = ""
        updatePlay()
        updateTiming()
    }

    override fun onStart() {
        super.onStart()
        started = true
        pager.post { settlePage() }
    }

    override fun onStop() {
        started = false
        snapshotVideo()
        releaseVideo()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        snapshotVideo()
        outState.putInt("page", page)
        outState.putLong("time", resumeAt)
        outState.putBoolean("playing", resumePlaying)
        outState.putFloat("rate", rate)
        super.onSaveInstanceState(outState)
    }

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    private inner class Pages : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (previewKind(items[position].mime) == PreviewKind.VIDEO) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val view = if (type == 0) PreviewPhotoPage(parent.context) else PreviewVideoPage(parent.context).apply {
                setOnClickListener { if (this === videoPage) togglePlayback() }
            }
            view.layoutParams = RecyclerView.LayoutParams(-1, -1)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            when (val view = holder.itemView) {
                is PreviewPhotoPage -> view.bind(item.uri(this@MediaPreviewActivity), item.name)
                is PreviewVideoPage -> {
                    view.contentDescription = item.name
                    view.aspect(0f)
                    view.report(null)
                }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            when (val view = holder.itemView) {
                is PreviewPhotoPage -> view.clear()
                is PreviewVideoPage -> if (view === videoPage) {
                    snapshotVideo()
                    releaseVideo()
                }
            }
        }
    }

    private companion object {
        const val SECONDARY = 0xffbdc2cc.toInt()
        val RATES = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

        fun speedLabel(speed: Float) = speed.toString().removeSuffix(".0") + "×"

        fun clock(ms: Long): String {
            val seconds = ms / 1000
            return if (seconds < 3600) String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
            else String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        }

        fun trackLabels(tracks: Tracks): String {
            fun selected(type: Int): Format? {
                for (group in tracks.groups) {
                    if (group.type != type) continue
                    for (index in 0 until group.length) if (group.isTrackSelected(index)) return group.getTrackFormat(index)
                }
                return null
            }
            val video = selected(C.TRACK_TYPE_VIDEO)
            val audio = selected(C.TRACK_TYPE_AUDIO)
            val videoLabel = when {
                video?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "片源 Dolby Vision"
                video?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "片源 HDR (PQ)"
                video?.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "片源 HDR (HLG)"
                else -> null
            }
            val audioLabel = when (audio?.sampleMimeType) {
                MimeTypes.AUDIO_AC3 -> "Dolby Digital"
                MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
                MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos 音轨"
                MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
                else -> if (audio == null && tracks.containsType(C.TRACK_TYPE_AUDIO)) "设备不支持该音轨" else null
            }
            return listOfNotNull(videoLabel, audioLabel).joinToString(" · ")
        }
    }
}
