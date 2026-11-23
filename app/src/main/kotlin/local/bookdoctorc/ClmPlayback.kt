package local.bookdoctorc

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import com.example.clmplayer.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

/** Owns one CLM stream and the last position while the app moves between tabs. */
@androidx.annotation.OptIn(UnstableApi::class)
class ClmPlayback private constructor(context: Context) {
    data class LastListened(val item: LibraryItem, val title: String, val index: Int)

    private data class HeardAlbum(val id: String, val title: String, val item: LibraryItem)

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("clm_player", Context.MODE_PRIVATE)
    private val library = Library.get(app)
    private val controller = ClmPlayerController(app)
    private val main = Handler(Looper.getMainLooper())
    private val archiveIo = Executors.newSingleThreadExecutor()
    private val scanIo = Executors.newSingleThreadExecutor()
    private var archive: ClmArchive? = null
    private var current: LibraryItem? = null
    private var archiveRequest: CancellationSignal? = null
    private var scanRequest: CancellationSignal? = null
    private var scanningTree: Uri? = null
    private var foreground = false
    private var opening = false
    private var preparing = false
    private var hasResume = false
    private var clockStart = 0L
    private var clockWall = 0L
    private val tick = Runnable { updatePlayback() }
    private val nextScan = Runnable { refresh() }
    private val listeners = LinkedHashSet<Runnable>()
    private var heard = restoreRecents()
    private var historical = emptyList<HeardAlbum>()
    private var scanned = emptyMap<String, LibraryItem>()
    private var following = emptyMap<String, LibraryItem>()

    val catalog = AudiobookCatalog(app)
    var recentEntries: List<LibraryItem> = emptyList()
        private set
    var observer: Runnable? = null
    var entries: List<LibraryItem> = emptyList()
        private set
    var scanning = false
        private set
    var scanError: String? = null
        private set
    var error: String? = null
        private set
    var openingTitle: String? = null
        private set
    var cover: Bitmap? = null
        private set
    internal var notificationArtwork: Bitmap? = null
        private set
    var trackNames: List<String> = emptyList()
        private set
    var selected = 0
        private set
    var duration = 0L
        private set
    var position = 0L
        private set
    var playing = false
        private set
    var speed = prefs.getFloat("global_speed", 1f).coerceIn(.5f, 2f)
        private set
    var intro = prefs.getInt("global_intro_seconds", 0).coerceIn(0, 86400)
        private set
    var outro = prefs.getInt("global_outro_seconds", 0).coerceIn(0, 86400)
        private set

    val albumId: String? get() = archive?.id
    val albumTitle: String? get() = archive?.title
    val busy: Boolean get() = opening || preparing
    val wantsPlayback: Boolean get() = controller.wantsPlayback()
    val canSeek: Boolean get() = !busy && duration > 0 && controller.canSeek()
    internal val canSkipNext: Boolean get() = selected + 1 < trackNames.size || nextAlbum() != null
    val tree: Uri? get() = prefs.getString("library_tree", null)?.let(Uri::parse)

    init {
        controller.onPrepared = { length, start ->
            duration = length
            position = start
            hasResume = true
            save()
            notifyChanged()
        }
        controller.onPlayState = { value ->
            if (playing != value) {
                if (playing) {
                    checkpoint()
                    if (controller.isActive()) position = controller.currentPosition()
                    save()
                    main.removeCallbacks(tick)
                }
                playing = value
                if (playing) {
                    clockStart = SystemClock.elapsedRealtime()
                    clockWall = System.currentTimeMillis()
                    recordHeard()
                    main.postDelayed(tick, 220)
                }
                notifyChanged()
            }
        }
        controller.onPlaybackIntentChanged = ::notifyChanged
        controller.onLoading = { loading -> preparing = loading; notifyChanged() }
        controller.onCompleted = { next() }
        controller.onError = { message -> error = message; notifyChanged() }
        rebuildRecents()
        if (heard.size < RECENT_LIMIT) loadHistoricalRecents()
        prefs.getString("last_clm_uri", null)?.let { last ->
            open(LibraryItem(Uri.parse(last), prefs.getString("last_clm_name", "CLM") ?: "CLM",
                prefs.getString("last_clm_folder", "打开的文件") ?: "打开的文件", 0), auto = false)
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            refresh()
            if (wantsPlayback) AudioMediaService.ensureRunning(app)
        } else {
            main.removeCallbacks(nextScan)
            saveCurrent()
        }
    }

    fun setTree(uri: Uri) {
        prefs.edit().putString("library_tree", uri.toString()).apply()
        entries = emptyList()
        scanned = emptyMap()
        following = emptyMap()
        rebuildRecents()
        refresh()
    }

    fun refresh() {
        val uri = tree
        if (scanRequest != null && scanningTree == uri) return
        scanRequest?.cancel()
        val cancellation = CancellationSignal()
        scanRequest = cancellation
        scanningTree = uri
        main.removeCallbacks(nextScan)
        scanning = true
        scanError = null
        notifyChanged()
        scanIo.execute {
            val result = runCatching {
                val imported = ModuleTransfer.podcasts(library.readableDatabase)
                imported + if (uri == null) emptyList() else LibraryScanner.scan(app, uri, cancellation)
            }
            main.post {
                if (scanRequest !== cancellation) return@post
                scanRequest = null
                scanningTree = null
                scanning = false
                result.fold(
                    onSuccess = { sources ->
                        if (entries != sources) {
                            entries = sources
                            scanned = sources.associateBy { sourceId(it) }
                            following = buildMap {
                                for (index in 0 until sources.lastIndex) {
                                    val album = sources[index]
                                    val next = sources[index + 1]
                                    if (album.folderId == next.folderId) put(sourceId(album), next)
                                }
                            }
                            rebuildRecents()
                            val book = archive
                            val item = current
                            if (book != null && item != null) {
                                val refreshed = scanned[sourceId(item)] ?: item
                                if (sameRevision(refreshed, item)) catalog.remember(refreshed, book.title, book.cover)
                            }
                        }
                    },
                    onFailure = { scanError = it.localizedMessage ?: "无法扫描目录" }
                )
                notifyChanged()
                if (foreground) main.postDelayed(nextScan, 30_000)
            }
        }
    }

    fun open(item: LibraryItem) {
        MusicPlayback.claimPodcast(app)
        val source = scanned[sourceId(item)] ?: item
        val loaded = current
        val sameArchive = loaded != null && sourceId(source) == sourceId(loaded) && sameRevision(source, loaded)
        if (!opening && sameArchive) {
            if (!wantsPlayback && !busy) toggle()
        } else open(source, auto = true)
    }

    private fun open(item: LibraryItem, auto: Boolean, fresh: Boolean = false) {
        archiveRequest?.cancel()
        val cancellation = CancellationSignal()
        archiveRequest = cancellation
        saveCurrent()
        opening = true
        openingTitle = catalog.preview(item).title
        controller.stop()
        if (auto) {
            MusicPlayback.claimPodcast(app)
            AudioMediaService.ensureRunning(app)
        }
        error = null
        notifyChanged()
        archiveIo.execute {
            val loaded = runCatching {
                val book = ClmArchiveReader.read(app, item.uri, cancellation)
                try {
                    Triple(book, CoverDecoder.decode(book.cover, 1200)?.apply { prepareToDraw() },
                        CoverDecoder.decode(book.cover, AudiobookCatalog.NOTIFICATION_SIDE)?.apply { prepareToDraw() })
                } catch (error: Exception) {
                    book.close()
                    throw error
                }
            }
            main.post {
                if (archiveRequest !== cancellation) {
                    loaded.getOrNull()?.let { (book, image, small) ->
                        book.close()
                        image?.recycle()
                        small?.recycle()
                    }
                    return@post
                }
                archiveRequest = null
                opening = false
                openingTitle = null
                loaded.fold(onSuccess = { (book, image, small) ->
                    archive?.close()
                    archive = book
                    cover = image
                    notificationArtwork = small
                    current = item
                    val revision = scanned[sourceId(item)]?.takeIf { sameRevision(it, item) } ?: item
                    catalog.remember(revision, book.title, book.cover)
                    trackNames = book.tracks.map { track ->
                        val name = track.name
                        if (name.endsWith(".m4a", ignoreCase = true)) name.dropLast(4).trimEnd().ifEmpty { "未命名音轨" }
                        else name
                    }
                    prefs.edit().putString("last_clm_uri", item.uri.toString())
                        .putString("last_clm_name", item.name)
                        .putString("last_clm_folder", item.folder).apply()
                    val previous = if (fresh) null else (prefs.getString("resume:" + book.id, null)
                        ?: ModuleTransfer.resume(library.readableDatabase, book.id))?.split('|')
                    selected = previous?.getOrNull(0)?.toIntOrNull()?.coerceIn(book.tracks.indices) ?: 0
                    position = previous?.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                    hasResume = previous != null && previous.getOrNull(2) == "saved"
                    duration = 0
                    if (auto) playTrack(selected, fresh)
                }, onFailure = { error = it.localizedMessage ?: "无法打开 CLM" })
                notifyChanged()
            }
        }
    }

    fun toggle() {
        if (opening) return
        if (wantsPlayback) pause() else resume()
    }

    internal fun pause() {
        if (opening) {
            archiveRequest?.cancel()
            archiveRequest = null
            opening = false
            openingTitle = null
        }
        saveCurrent()
        controller.pause()
        notifyChanged()
    }

    internal fun resume() {
        if (opening || archive == null || wantsPlayback) return
        MusicPlayback.claimPodcast(app)
        AudioMediaService.ensureRunning(app)
        if (controller.isActive()) {
            if (controller.isEnded() || window().reachesOutro(position)) next()
            else {
                controller.resume()
                notifyChanged()
            }
        } else {
            playTrack(selected, false)
        }
    }

    fun selectTrack(index: Int) = playTrack(index, true)

    private fun playTrack(index: Int, fresh: Boolean) {
        val book = archive ?: return
        if (opening || index !in book.tracks.indices) return
        MusicPlayback.claimPodcast(app)
        AudioMediaService.ensureRunning(app)
        saveCurrent()
        controller.stop()
        val offset = if (!fresh && index == selected && hasResume) position else null
        selected = index
        position = offset ?: 0
        hasResume = offset != null
        duration = 0
        error = null
        save()
        controller.play(book, book.tracks[index], offset, speed, intro * 1000L, outro * 1000L)
        notifyChanged()
    }

    fun previous() {
        if (selected > 0) selectTrack(selected - 1)
    }

    fun next() {
        val book = archive ?: return
        if (opening) return
        val next = nextTrackIndex(selected, book.tracks.size)
        if (next != null) {
            selectTrack(next)
            return
        }
        val album = nextAlbum()
        if (album != null) {
            open(album, auto = true, fresh = true)
        } else {
            saveCurrent()
            controller.stop()
            position = 0
            hasResume = false
            save()
            notifyChanged()
        }
    }

    private fun nextAlbum(): LibraryItem? = current?.let { following[sourceId(it)] }

    fun seekTo(value: Long) {
        if (!canSeek) return
        position = value.coerceIn(0, duration)
        hasResume = true
        controller.seekTo(position)
        save()
        if (wantsPlayback && window().reachesOutro(position)) controller.completeForOutro()
        notifyChanged()
    }

    fun updateSettings(newSpeed: Float, headSeconds: Int, tailSeconds: Int) {
        speed = newSpeed.coerceIn(.5f, 2f)
        intro = headSeconds.coerceIn(0, 86400)
        outro = tailSeconds.coerceIn(0, 86400)
        prefs.edit().putFloat("global_speed", speed).putInt("global_intro_seconds", intro)
            .putInt("global_outro_seconds", outro).apply()
        controller.setSpeed(speed)
        notifyChanged()
    }

    private fun window() = PlaybackWindow.create(duration, intro * 1000L, outro * 1000L)

    private fun updatePlayback() {
        if (!playing) return
        position = controller.currentPosition()
        if (SystemClock.elapsedRealtime() - clockStart >= 5000) {
            checkpoint()
            save()
        }
        if (window().reachesOutro(position)) controller.completeForOutro()
        else {
            notifyChanged()
            main.postDelayed(tick, 220)
        }
    }

    private fun checkpoint() {
        val book = archive ?: return
        val uri = current?.uri ?: return
        val now = SystemClock.elapsedRealtime()
        var start = clockWall
        val end = start + (now - clockStart).coerceAtLeast(0)
        val zone = ZoneId.systemDefault()
        while (start < end) {
            val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val stop = minOf(end, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            library.addListeningTime(book.id, uri.toString(), book.title, date.toString(), stop - start)
            start = stop
        }
        clockStart = now
        clockWall = System.currentTimeMillis()
    }

    fun saveCurrent() {
        if (playing) checkpoint()
        if (controller.isActive() && duration > 0) position = controller.currentPosition()
        save()
    }

    private fun save() {
        val id = albumId ?: return
        val update = prefs.edit().putString("resume:" + id, "$selected|$position|" + if (hasResume) "saved" else "fresh")
        if (hasResume && duration > 0 && prefs.getInt("heard_track:" + id, -1) == selected) {
            update.putLong("heard_duration:" + id, duration)
        }
        update.apply()
    }

    fun restored() {
        heard = restoreRecents()
        rebuildRecents()
        refresh()
    }

    fun currentItem(): LibraryItem? = current

    fun lastListened(): LastListened? {
        val record = heard.firstOrNull() ?: historical.firstOrNull() ?: return null
        val item = scanned[sourceId(record.item)] ?: record.item
        val legacyTrack = prefs.getString("resume:" + record.id, null)
            ?.substringBefore('|')?.toIntOrNull() ?: 0
        val index = prefs.getInt("heard_track:" + record.id, legacyTrack).coerceAtLeast(0)
        return LastListened(item, record.title.ifBlank { catalog.smallPreview(item).title }, index)
    }

    fun listenedPercent(item: LibraryItem): Int? {
        val loaded = current
        if (loaded != null && sourceId(loaded) == sourceId(item) && duration > 0 && hasResume) {
            return (position * 100.0 / duration).toInt().coerceIn(0, 100)
        }
        val id = (heard.firstOrNull { sourceId(it.item) == sourceId(item) }
            ?: historical.firstOrNull { sourceId(it.item) == sourceId(item) })?.id ?: return 0
        val length = prefs.getLong("heard_duration:" + id, 0L)
        val millis = prefs.getString("resume:" + id, null)?.split('|')?.getOrNull(1)?.toLongOrNull()
        return if (length > 0 && millis != null) (millis * 100.0 / length).toInt().coerceIn(0, 100) else null
    }

    fun addObserver(listener: Runnable) { listeners.add(listener) }
    fun removeObserver(listener: Runnable) { listeners.remove(listener) }

    private fun notifyChanged() {
        observer?.run()
        listeners.toList().forEach { it.run() }
    }

    private fun recordHeard() {
        val book = archive ?: return
        val current = current ?: return
        val item = scanned[sourceId(current)] ?: current
        prefs.edit().putInt("heard_track:" + book.id, selected)
            .putLong("heard_duration:" + book.id, duration).apply()
        val record = HeardAlbum(book.id, book.title.take(512), item)
        if (record == heard.firstOrNull()) return
        heard = (listOf(record) + heard.filterNot {
            it.id == book.id || sourceId(it.item) == sourceId(item)
        }).take(RECENT_LIMIT)
        val rows = JSONArray()
        for (album in heard) rows.put(JSONObject()
            .put("id", album.id).put("title", album.title)
            .put("uri", album.item.uri.toString()).put("name", album.item.name)
            .put("folder", album.item.folder).put("bytes", album.item.bytes)
            .put("modified", album.item.modified).put("folderId", album.item.folderId))
        prefs.edit().putString("recent_albums", rows.toString()).apply()
        rebuildRecents()
    }

    private fun restoreRecents(): List<HeardAlbum> {
        val json = runCatching { JSONArray(prefs.getString("recent_albums", "[]") ?: "[]") }.getOrNull()
            ?: return emptyList()
        return (0 until minOf(json.length(), RECENT_LIMIT)).mapNotNull { index ->
            val album = json.optJSONObject(index) ?: return@mapNotNull null
            val uri = Uri.parse(album.optString("uri"))
            val id = album.optString("id")
            if (uri.scheme != "content" || id.isBlank()) return@mapNotNull null
            val folder = album.optString("folder", "打开的文件")
            HeardAlbum(id, album.optString("title"), LibraryItem(
                uri, album.optString("name", "CLM"), folder, album.optLong("bytes"),
                album.optLong("modified"), album.optString("folderId", folder)))
        }
    }

    private fun loadHistoricalRecents() {
        scanIo.execute {
            val past = runCatching {
                library.listeningDurations(null).mapNotNull { row ->
                    val uri = Uri.parse(row.uri())
                    if (uri.scheme != "content" || row.millis() <= 0) null
                    else HeardAlbum(row.id(), row.title(), LibraryItem(uri, row.title(), "打开的文件", 0))
                }
            }.getOrDefault(emptyList())
            main.post {
                historical = past
                rebuildRecents()
                notifyChanged()
            }
        }
    }

    private fun rebuildRecents() {
        val ids = HashSet<String>()
        val sources = HashSet<String>()
        val recent = ArrayList<LibraryItem>(RECENT_LIMIT)
        for (album in heard + historical) {
            val key = sourceId(album.item)
            if (album.id in ids || key in sources) continue
            val item = scanned[key] ?: album.item
            ids.add(album.id)
            sources.add(key)
            if (sameRevision(item, album.item)) catalog.seedTitle(item, album.title)
            recent.add(item)
            if (recent.size == RECENT_LIMIT) break
        }
        if (recentEntries != recent) recentEntries = recent
    }

    fun historyItem(uriText: String, title: String): LibraryItem {
        val uri = Uri.parse(uriText)
        val source = LibraryScanner.sourceId(uri)
        return scanned[source] ?: recentEntries.firstOrNull { sourceId(it) == source }
            ?: LibraryItem(uri, title, "打开的文件", 0)
    }

    private fun sameRevision(first: LibraryItem, second: LibraryItem): Boolean =
        (first.bytes == 0L || second.bytes == 0L || first.bytes == second.bytes) &&
            (first.modified == 0L || second.modified == 0L || first.modified == second.modified)

    private fun sourceId(item: LibraryItem): String = LibraryScanner.sourceId(item.uri)

    companion object {
        private const val RECENT_LIMIT = 12
        private var shared: ClmPlayback? = null

        @JvmStatic fun get(context: Context): ClmPlayback =
            shared ?: ClmPlayback(context.applicationContext).also { shared = it }
    }
}
