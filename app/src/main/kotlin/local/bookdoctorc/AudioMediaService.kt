package local.bookdoctorc

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

/** Publishes the existing CLM engine through Android's lock-screen and media controls. */
class AudioMediaService : Service() {
    private data class Track(val title: String, val album: String, val length: Long, val art: Bitmap?)
    private data class Controls(val pause: Boolean, val previous: Boolean, val next: Boolean)
    private data class Position(val state: Int, val value: Long, val speed: Float, val actions: Long, val time: Long) {
        fun at(now: Long): Long =
            if (state == PlaybackState.STATE_PLAYING) value + ((now - time) * speed).toLong() else value
    }

    private lateinit var playback: ClmPlayback
    private lateinit var music: MusicPlayback
    private val isMusic: Boolean get() = music.isActive && music.current != null
    private lateinit var session: MediaSession
    private lateinit var notifications: NotificationManager
    private lateinit var playerIntent: PendingIntent
    private val main = Handler(Looper.getMainLooper())
    private val idle = Runnable { stopPlayback() }
    private val observer = Runnable { sync() }
    private var track: Track? = null
    private var controls: Controls? = null
    private var position: Position? = null
    private var showing = false
    private var idleScheduled = false

    override fun onCreate() {
        super.onCreate()
        started = true
        playback = ClmPlayback.get(this)
        music = MusicPlayback.get(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "音频播放", NotificationManager.IMPORTANCE_LOW))
        playerIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setAction(OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession(this, "MookDoctorC 音频")
        session.setSessionActivity(playerIntent)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = command(PLAY)
            override fun onPause() = command(PAUSE)
            override fun onSkipToPrevious() = command(PREVIOUS)
            override fun onSkipToNext() = command(NEXT)
            override fun onStop() = stopPlayback()
            override fun onSeekTo(pos: Long) {
                if (isMusic) music.seekTo(pos) else playback.seekTo(pos)
                sync(forcePosition = true)
            }
        }, main)
        playback.addObserver(observer)
        music.addObserver(observer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopPlayback()
        } else {
            if (!showing) {
                sync()
                session.isActive = true
                startForeground(NOTIFICATION_ID, notification())
                showing = true
            }
            intent?.action?.let(::command)
        }
        return START_NOT_STICKY
    }

    private fun command(action: String) {
        if (isMusic) {
            when (action) {
                PLAY -> music.resume()
                PAUSE -> music.pause()
                PREVIOUS -> music.previous()
                NEXT -> music.next()
            }
            sync(forcePosition = true)
            return
        }
        when (action) {
            PLAY -> playback.resume()
            PAUSE -> playback.pause()
            PREVIOUS -> if (!playback.busy) playback.previous()
            NEXT -> if (!playback.busy && playback.canSkipNext) playback.next()
        }
        sync(forcePosition = true)
    }

    private fun sync(forcePosition: Boolean = false) {
        val musicActive = isMusic
        val opening = if (musicActive) null else playback.openingTitle
        val album = if (musicActive) music.artist else opening ?: playback.albumTitle.orEmpty()
        val title = when {
            musicActive -> music.title
            opening != null -> "正在读取音轨…"
            album.isEmpty() -> "音频播放"
            else -> playback.trackNames.getOrNull(playback.selected) ?: album
        }
        val next = if (musicActive) Track(title, album, music.duration, music.notificationArtwork)
            else Track(title, album, if (opening == null) playback.duration else 0,
                if (opening == null) playback.notificationArtwork else null)
        val buttons = if (musicActive) Controls(music.wantsPlayback, music.canSkipPrevious, music.canSkipNext)
            else Controls(opening != null || playback.wantsPlayback,
                opening == null && !playback.busy && playback.selected > 0,
                opening == null && !playback.busy && playback.canSkipNext)
        val trackChanged = track != next
        val refreshNotification = trackChanged || controls != buttons
        if (trackChanged) {
            track = next
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, album)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, next.length)
            if (next.art != null) metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, next.art)
            session.setMetadata(metadata.build())
        }
        controls = buttons

        var actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_STOP
        if (buttons.previous) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (buttons.next) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (if (musicActive) music.duration > 0 else playback.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
        val state = when {
            opening != null || (if (musicActive) music.busy else playback.busy) && buttons.pause -> PlaybackState.STATE_BUFFERING
            (if (musicActive) music.playing else playback.playing) -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val now = SystemClock.elapsedRealtime()
        val speed = if (state == PlaybackState.STATE_PLAYING) { if (musicActive) 1f else playback.speed } else 0f
        val current = Position(state, if (musicActive) music.position else if (opening == null) playback.position else 0, speed, actions, now)
        val previous = position
        if (forcePosition || trackChanged || previous == null || previous.state != state || previous.actions != actions ||
            previous.speed != speed || abs(previous.at(now) - current.value) > 400) {
            position = current
            session.setPlaybackState(PlaybackState.Builder().setActions(actions)
                .setState(state, current.value, speed, now).build())
        }

        if (refreshNotification && showing) updateNotification()
        if (buttons.pause) {
            if (idleScheduled) main.removeCallbacks(idle)
            idleScheduled = false
        } else if (!idleScheduled) {
            idleScheduled = true
            main.postDelayed(idle, 10 * 60_000L)
        }
    }

    // Android exempts MediaStyle notifications with a live MediaSession token.
    @SuppressLint("NotificationPermission")
    private fun updateNotification() = notifications.notify(NOTIFICATION_ID, notification())

    private fun notification(): Notification {
        val media = track
        val buttons = controls
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cd_media)
            .setContentTitle(media?.title ?: "音频播放")
            .setContentText(media?.album)
            .setLargeIcon(media?.art)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(playerIntent)
            .setDeleteIntent(action(STOP, 5))
            .setStyle(style)
        var count = 0
        if (buttons?.previous == true) {
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), if (isMusic) "上一首" else "上一集", action(PREVIOUS, 1)).build())
            count++
        }
        val pause = buttons?.pause == true
        builder.addAction(Notification.Action.Builder(
            Icon.createWithResource(this, if (pause) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (pause) "暂停" else "播放", action(if (pause) PAUSE else PLAY, 2)).build())
        count++
        if (buttons?.next == true) {
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), if (isMusic) "下一首" else "下一集", action(NEXT, 3)).build())
            count++
        }
        style.setShowActionsInCompactView(*IntArray(count) { it })
        return builder.build()
    }

    private fun action(name: String, request: Int): PendingIntent = PendingIntent.getService(this, request,
        Intent(this, AudioMediaService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun stopPlayback() {
        showing = false
        if (isMusic) music.pause() else playback.pause()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!(if (isMusic) music.wantsPlayback else playback.wantsPlayback) && playback.openingTitle == null) stopPlayback()
    }

    override fun onDestroy() {
        started = false
        main.removeCallbacksAndMessages(null)
        playback.removeObserver(observer)
        music.removeObserver(observer)
        if (playback.wantsPlayback || playback.openingTitle != null) playback.pause()
        session.release()
        super.onDestroy()
    }

    companion object {
        const val OPEN_PLAYER = "local.bookdoctorc.OPEN_PLAYER"
        private const val CHANNEL = "bookdoctorc.cd.playback"
        private const val NOTIFICATION_ID = 321
        private const val PLAY = "play"
        private const val PAUSE = "pause"
        private const val PREVIOUS = "previous"
        private const val NEXT = "next"
        private const val STOP = "stop"
        private var started = false

        fun ensureRunning(context: Context) {
            if (started) return
            try {
                context.startForegroundService(Intent(context, AudioMediaService::class.java))
                started = true
            } catch (error: IllegalStateException) {
                Log.w("BookDoctorC", "Could not start the media service from the background", error)
            }
        }
    }
}
