package local.bookdoctorc

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.concurrent.Executors
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

internal fun musicResumePosition(saved: Long, duration: Long): Long {
    if (duration <= 0 || saved <= 0) return 0
    val ending = minOf(1_000L, duration / 10)
    return if (saved < duration - ending) saved else 0
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class MusicPlayback private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("music", Context.MODE_PRIVATE)
    private val browseSeed = if (prefs.contains("browse_seed")) prefs.getLong("browse_seed", 0L)
        else Random.nextLong().also { prefs.edit().putLong("browse_seed", it).apply() }
    private val db = Library.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val scanWork = Executors.newSingleThreadExecutor()
    private val statsWork = Executors.newSingleThreadExecutor()
    private val metadataWork = Executors.newFixedThreadPool(2)
    private val playlistWork = Executors.newSingleThreadExecutor()
    private val metadataDisk = MusicMetadataStore(File(app.filesDir, "music-metadata"))
    private val queue = MusicQueue()
    private val observers = LinkedHashSet<Runnable>()
    private val pending = HashSet<MusicStore.Revision>()
    private val pendingPreviews = HashSet<MusicStore.Revision>()
    private val pendingArtwork = HashSet<MusicStore.Revision>()
    private val artworkUnavailable = LruCache<MusicStore.Revision, Boolean>(256)
    private val previews = object : LruCache<MusicStore.Revision, MusicMetadata.Preview>(24 * 1024 * 1024) {
        override fun sizeOf(key: MusicStore.Revision, value: MusicMetadata.Preview): Int =
            512 + (value.thumbnail?.allocationByteCount ?: 0) + 2 * (value.title.length + value.artist.length)
    }
    private val artwork = object : LruCache<MusicStore.Revision, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: MusicStore.Revision, value: Bitmap): Int = value.allocationByteCount
    }
    private val info = object : LruCache<MusicStore.Revision, MusicMetadata.Info>(12 * 1024 * 1024) {
        override fun sizeOf(key: MusicStore.Revision, value: MusicMetadata.Info): Int =
            1024 + (value.art?.allocationByteCount ?: 0) + (value.thumbnail?.takeIf { it !== value.art }?.allocationByteCount ?: 0) + value.lyrics.sumOf { 48 + it.text.length * 2 }
    }
    private var selectedKey: MusicStore.Revision? = null
    private var selectedInfo: MusicMetadata.Info? = null
    private var listeningSince = 0L
    private var listeningWall = 0L
    private var listeningSong: String? = null
    var listeningRevision = 0
        private set
    private var notificationCover: Bitmap? = null
    private var notificationSource: Bitmap? = null
    private val stats = HashMap<String, MusicStore.Song>()
    private val previous = ArrayDeque<String>()
    private var scan: CancellationSignal? = null
    private var source = prefs.getString("tree", null)?.let(Uri::parse)
    private var counted = false
    private var pendingStart: Long? = null
    private var statsAt = 0L
    private var restoredInitial = false
    private var restoreIndexedSongs = true
    private val tick = Runnable { pulse() }
    private var engine: ExoPlayer? = null
    var tracks: List<MusicStore.Song> = emptyList()
        private set
    var initialSongs: List<MusicStore.Song> = emptyList()
        private set
    var playlists: List<MusicStore.Playlist> = emptyList()
        private set
    var history: List<MusicStore.Song> = emptyList()
        private set
    var current: MusicStore.Song? = null
        private set
    var scanning = false
        private set
    var problem: String? = null
        private set
    var libraryProblem: String? = null
        private set
    var revision = 0
        private set
    var byAdded = prefs.getBoolean("sort_added", false)
        private set
    var descending = prefs.getBoolean("descending", false)
        private set
    var mode = runCatching { MusicQueue.Mode.valueOf(prefs.getString("mode", "SEQUENCE")!!) }.getOrDefault(MusicQueue.Mode.SEQUENCE)
        private set

    val tree: Uri? get() = source
    val isActive: Boolean get() = prefs.getBoolean("active", false)
    val playing: Boolean get() = engine?.isPlaying == true
    val wantsPlayback: Boolean get() = engine?.let { it.playWhenReady && it.playbackState != Player.STATE_ENDED && it.playbackState != Player.STATE_IDLE } == true
    val busy: Boolean get() = engine?.playbackState == Player.STATE_BUFFERING
    val duration: Long get() = engine?.duration?.takeIf { it > 0 } ?: current?.let { cachedPreview(it)?.duration } ?: 0
    val position: Long get() = engine?.currentPosition ?: current?.let { stats[it.key]?.position ?: it.position } ?: 0
    val cover: Bitmap? get() = current?.let { cachedArtwork(it) ?: cachedPreview(it)?.thumbnail }
    val notificationArtwork: Bitmap? get() {
        val source = cover
        if (source !== notificationSource) {
            notificationSource = source
            notificationCover = source?.let {
                val scale = 256f / maxOf(it.width, it.height)
                if (scale >= 1) it else Bitmap.createScaledBitmap(it, (it.width * scale).toInt().coerceAtLeast(1),
                    (it.height * scale).toInt().coerceAtLeast(1), true)
            }
        }
        return notificationCover
    }
    val title: String get() = current?.let { cachedPreview(it)?.title ?: it.displayName }.orEmpty()
    val artist: String get() = current?.let { cachedPreview(it)?.artist ?: it.folder.substringAfterLast('/') }.orEmpty()
    val album: String get() = current?.folder?.substringAfterLast('/').orEmpty()
    val canSkipNext: Boolean get() = chooseNext(false) != null
    val canSkipPrevious: Boolean get() = position > 3000 || previous.isNotEmpty() || listPosition() > 0

    fun addObserver(observer: Runnable) { observers.add(observer) }
    fun removeObserver(observer: Runnable) { observers.remove(observer) }
    private fun changed() { observers.toList().forEach(Runnable::run) }

    fun setTree(uri: Uri) {
        if (source != uri) {
            initialSongs = emptyList()
            restoreIndexedSongs = false
        }
        source = uri
        prefs.edit().putString("tree", uri.toString()).apply()
        refresh(true)
    }

    fun refresh(force: Boolean = false) {
        if (scan != null && !force) return
        scan?.cancel()
        val signal = CancellationSignal()
        val directory = source
        scan = signal
        scanning = true
        libraryProblem = null
        val restore = !restoredInitial && restoreIndexedSongs
        val added = byAdded
        val reverse = descending
        val last = prefs.getString("last", null)
        changed()
        scanWork.execute {
            val database = db.writableDatabase
            val old = runCatching { MusicStore.songs(database) }.getOrDefault(emptyList())
            if (restore && old.isNotEmpty() && !signal.isCanceled) {
                val records = playedRecords(database)
                val order = initialOrder(old)
                val groups = MusicStore.playlists(old, added, reverse)
                val recentFolder = records.maxByOrNull { it.lastPlayed }?.folderId
                val featured = (groups.firstOrNull { it.id == recentFolder } ?: groups.lastOrNull())?.songs?.firstOrNull()
                val firstCovers = (order + groups.take(8).map { it.songs.first() } +
                    listOfNotNull(featured, old.firstOrNull { it.key == last })).distinctBy { it.revision }
                // Warm only saved images. Source extraction stays lazy, and rows mount with these images already set.
                val warm = firstCovers.mapNotNull { song ->
                    if (signal.isCanceled) return@execute
                    runCatching { metadataDisk.readPreview(song) }.getOrNull()?.let { song.revision to it }
                }
                val featuredCover = featured?.let { runCatching { metadataDisk.readArtwork(it) }.getOrNull() }
                main.post {
                    if (scan !== signal) return@post
                    warm.forEach { (key, preview) -> previews.put(key, preview) }
                    if (featured != null && featuredCover != null) artwork.put(featured.revision, featuredCover)
                    initialSongs = order
                    publishSongs(old, records)
                }
            }
            if (signal.isCanceled) return@execute
            val result = if (directory == null) Result.success(old) else
                runCatching { MusicStore.scan(app, database, directory, signal) }
            val records = playedRecords(database)
            main.post {
                if (scan !== signal) return@post
                scan = null
                scanning = false
                val latest = result.getOrElse {
                    libraryProblem = "无法扫描音乐目录，请在「我的」重新授权"
                    old
                }
                publishSongs(latest, records)
            }
        }
    }

    private fun playedRecords(database: android.database.sqlite.SQLiteDatabase): List<MusicStore.Song> =
        runCatching { MusicStore.songs(database, false).filter { it.lastPlayed > 0 } }.getOrDefault(emptyList())

    private fun initialOrder(songs: List<MusicStore.Song>): List<MusicStore.Song> =
        songs.shuffled(Random(browseSeed)).take(10)

    private fun publishSongs(latest: List<MusicStore.Song>, records: List<MusicStore.Song>) {
        for (item in records) {
            val session = stats[item.key]
            if (session == null || session.lastPlayed <= item.lastPlayed) stats[item.key] = item
        }
        tracks = latest
        if (initialSongs.isEmpty() && latest.isNotEmpty()) initialSongs = initialOrder(latest)
        rebuildLists()
        if (!restoredInitial) {
            restoredInitial = true
            current = find(prefs.getString("last", null))
        } else {
            current?.let { selected -> find(selected.key)?.let { current = it } }
        }
        current?.let(::request)
        changed()
    }

    fun setSort(added: Boolean, descending: Boolean) {
        byAdded = added
        this.descending = descending
        prefs.edit().putBoolean("sort_added", added).putBoolean("descending", descending).apply()
        rebuildLists()
        changed()
    }

    fun modeIndex(): Int = mode.ordinal
    fun currentModeName(): String = mode.label
    fun cycleMode() = setMode((mode.ordinal + 1) % MusicQueue.Mode.entries.size)
    fun setMode(index: Int) {
        mode = MusicQueue.Mode.entries[index]
        prefs.edit().putString("mode", mode.name).apply()
        changed()
    }

    private fun rebuildLists() {
        val ordered = MusicStore.playlists(tracks, byAdded, descending)
        if (ordered.map(::playlistLayout) != playlists.map(::playlistLayout)) {
            playlists = ordered
            revision++
        }
        val played = stats.values.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }
        if (played != history) history = played
    }

    private fun playlistLayout(playlist: MusicStore.Playlist) =
        Triple(playlist.id, playlist.path, playlist.songs.map { it.revision })

    fun cached(song: MusicStore.Song): MusicMetadata.Info? {
        val key = song.revision
        return selectedInfo?.takeIf { key == selectedKey } ?: info.get(key)
    }

    fun cachedPreview(song: MusicStore.Song): MusicMetadata.Preview? =
        previews.get(song.revision) ?: cached(song)?.preview

    fun cachedArtwork(song: MusicStore.Song): Bitmap? = artwork.get(song.revision) ?: cached(song)?.art

    fun preparePlaylistPreviews(songs: List<MusicStore.Song>, ready: () -> Unit) {
        val missing = songs.asSequence().take(16).filter { cachedPreview(it) == null }.toList()
        if (missing.isEmpty()) {
            ready()
            return
        }
        playlistWork.execute {
            val saved = missing.mapNotNull { song ->
                runCatching { metadataDisk.readPreview(song) }.getOrNull()?.let { song to it }
            }
            main.post {
                for ((song, preview) in saved) {
                    if (cachedPreview(song) == null) previews.put(song.revision, preview)
                }
                ready()
            }
        }
    }

    fun requestPreview(song: MusicStore.Song) {
        val key = song.revision
        if (cachedPreview(song) != null || key in pending || pendingPreviews.size >= 18 || !pendingPreviews.add(key)) return
        metadataWork.execute {
            val saved = runCatching { metadataDisk.readPreview(song) }.getOrNull()
            val extracted = if (saved == null) runCatching { MusicMetadata.read(app, song, metadataDisk) }.getOrNull() else null
            val preview = saved ?: extracted?.preview ?:
                MusicMetadata.Preview(song.displayName, song.folder.substringAfterLast('/'), 0, null)
            main.post {
                previews.put(key, preview)
                pendingPreviews.remove(key)
                if (extracted != null && current?.revision == key) rememberInfo(key, extracted)
                if (current?.revision == key) request(song)
                changed()
            }
        }
    }

    fun requestArtwork(song: MusicStore.Song) {
        val key = song.revision
        if (cachedArtwork(song) != null || key in pending || artworkUnavailable.get(key) != null ||
            pendingArtwork.size >= 8 || !pendingArtwork.add(key)) return
        metadataWork.execute {
            val cover = runCatching { metadataDisk.readArtwork(song) }.getOrNull()
            main.post {
                pendingArtwork.remove(key)
                if (cover == null) artworkUnavailable.put(key, true) else artwork.put(key, cover)
                changed()
            }
        }
    }

    fun request(song: MusicStore.Song) {
        val key = song.revision
        val isCurrent = current?.revision == key
        if (isCurrent && selectedKey != key) {
            selectedKey = key
            selectedInfo = info.get(key)
        }
        if (cached(song) != null || key in pendingPreviews || !pending.add(key)) return
        metadataWork.execute {
            val data = runCatching { MusicMetadata.read(app, song, metadataDisk) }.getOrElse {
                MusicMetadata.Info(song.displayName, song.folder.substringAfterLast('/'), null, 0, emptyList())
            }
            main.post {
                rememberInfo(key, data)
                pending.remove(key)
                changed()
            }
        }
    }

    private fun rememberInfo(key: MusicStore.Revision, data: MusicMetadata.Info) {
        if (current?.revision == key) {
            selectedKey = key
            selectedInfo = data
        }
        info.put(key, data)
        if (data.preview.thumbnail != null || previews.get(key)?.thumbnail == null) previews.put(key, data.preview)
        if (data.art != null) artworkUnavailable.remove(key)
    }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(app).build().apply {
        val player = this
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_LOCAL)
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    current?.let {
                        queue.mark(it.key)
                        listeningSong = it.key
                        listeningSince = SystemClock.elapsedRealtime()
                        listeningWall = System.currentTimeMillis()
                        if (!counted) save(true)
                    }
                    counted = true
                    main.removeCallbacks(tick)
                    main.postDelayed(tick, 250)
                } else {
                    main.removeCallbacks(tick)
                    save()
                    listeningSong = null
                }
                changed()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state != player.playbackState || !isSelectedMedia(player)) return
                if (state == Player.STATE_READY) {
                    val saved = pendingStart
                    pendingStart = null
                    if (saved != null && player.isCurrentMediaItemSeekable) {
                        val start = musicResumePosition(saved, player.duration)
                        if (start > 0) player.seekTo(start)
                    }
                }
                if (state == Player.STATE_ENDED && isActive && counted) {
                    save()
                    if (!next()) pause()
                }
                changed()
            }

            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) { changed() }
            override fun onPlayerError(error: PlaybackException) {
                problem = "无法播放这首曲目"
                changed()
            }
        })
    }

    private fun pulse() {
        if (!playing) return
        if (android.os.SystemClock.elapsedRealtime() - statsAt > 5_000) save()
        changed()
        main.postDelayed(tick, 250)
    }

    fun open(key: String): Boolean {
        val song = find(key) ?: return false
        if (song.key != current?.key || !wantsPlayback) select(song, resume = true, push = true)
        return true
    }

    private fun isSelectedMedia(player: Player): Boolean =
        current?.let { player.currentMediaItem?.mediaId == it.key } == true

    private fun select(song: MusicStore.Song, resume: Boolean, push: Boolean) {
        activate()
        val player = engine ?: buildPlayer().also { engine = it }
        val old = current
        if (old?.key != song.key && push && old != null) previous.addLast(old.key)
        save()
        player.pause()
        pendingStart = null
        current = song
        problem = null
        counted = false
        request(song)
        val item = MediaItem.Builder().setUri(song.uri).setMediaId(song.key).build()
        player.setMediaItem(item)
        // Prepare from the start so a stale position cannot end this item before its duration is known.
        pendingStart = if (resume) (stats[song.key]?.position ?: song.position).takeIf { it > 0 } else null
        prefs.edit().putString("last", song.key).apply()
        player.prepare()
        AudioMediaService.ensureRunning(app)
        player.play()
        changed()
    }

    private fun activate() {
        if (!isActive) prefs.edit().putBoolean("active", true).apply()
        ClmPlayback.get(app).pause()
    }

    private fun deactivated() {
        if (isActive) prefs.edit().putBoolean("active", false).apply()
        pause()
        changed()
    }

    fun pause() { engine?.pause(); save() }
    fun resume() {
        val song = current ?: return
        val player = engine
        if (player == null || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            select(song, resume = player?.playbackState != Player.STATE_ENDED, push = false)
        } else {
            activate()
            AudioMediaService.ensureRunning(app)
            player.play()
            changed()
        }
    }

    fun toggle() { if (wantsPlayback) pause() else resume() }
    fun seekTo(position: Long) {
        val song = current ?: return
        if (engine == null) select(song, resume = false, push = false)
        pendingStart = null
        engine?.seekTo(position.coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        save()
        changed()
    }

    fun next(): Boolean {
        val next = chooseNext(true) ?: return false
        select(next, resume = false, push = true)
        return true
    }

    fun previous() {
        if (position > 3000) { seekTo(0); return }
        while (previous.isNotEmpty()) {
            val song = find(previous.removeLast()) ?: continue
            select(song, resume = false, push = false)
            return
        }
        val list = currentList()
        val song = list.getOrNull(listPosition() - 1) ?: return
        select(song, resume = false, push = false)
    }

    fun currentList(): List<MusicStore.Song> = playlists.firstOrNull { it.id == current?.folderId }?.songs.orEmpty()
    private fun listPosition(): Int = currentList().indexOfFirst { it.key == current?.key }
    private fun find(key: String?): MusicStore.Song? = if (key == null) null else tracks.firstOrNull { it.key == key }
    private fun chooseNext(commit: Boolean): MusicStore.Song? {
        val id = current?.key ?: return null
        val lists = playlists.map { group -> group.songs.map { it.key } }
        val choice = queue.next(lists, id, mode, { key -> stats[key]?.plays ?: find(key)?.plays ?: 0 }, commit)
        return find(choice)
    }

    fun flushRecords() { statsWork.submit {}.get() }

    fun save(started: Boolean = false) {
        val song = current ?: return
        val player = engine ?: return
        saveListening()
        if (!isSelectedMedia(player) || pendingStart != null) return
        val at = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition.coerceAtLeast(0)
        val previous = stats[song.key] ?: song
        stats[song.key] = previous.copy(plays = previous.plays + if (started) 1 else 0,
            position = at, lastPlayed = if (started) System.currentTimeMillis() else previous.lastPlayed)
        if (started) history = stats.values.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed }
        statsAt = android.os.SystemClock.elapsedRealtime()
        statsWork.execute { runCatching { MusicStore.record(db.writableDatabase, song.key, started, at) } }
    }

    private fun saveListening() {
        val uri = listeningSong ?: return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - listeningSince
        if (elapsed <= 0) return
        val start = listeningWall
        listeningWall += elapsed
        listeningSince = now
        val zone = ZoneId.systemDefault()
        statsWork.execute {
            runCatching {
                var from = start
                val end = start + elapsed
                while (from < end) {
                    val day = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
                    val next = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val until = minOf(end, next)
                    MusicStore.addTime(db.writableDatabase, day.toString(), uri, until - from)
                    from = until
                }
            }.onSuccess {
                main.post {
                    listeningRevision++
                    changed()
                }
            }
        }
    }

    companion object {
        private var instance: MusicPlayback? = null
        @JvmStatic fun get(context: Context): MusicPlayback = instance ?: MusicPlayback(context).also { instance = it }
        fun claimPodcast(context: Context) { get(context).deactivated() }
    }
}
