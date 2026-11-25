package local.bookdoctorc

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import com.example.clmplayer.ClmArchiveReader
import com.example.clmplayer.CoverDecoder
import com.example.clmplayer.LibraryItem
import com.example.clmplayer.LibraryScanner
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Keeps display-sized images in memory and the original cover in app-private storage. */
class AudiobookCatalog(context: Context) {
    data class Preview(val title: String, val cover: Bitmap?)
    private data class Revision(val source: String, val bytes: Long, val modified: Long)
    private data class Request(val revision: Revision, val side: Int)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val disk = ClmCoverStore(File(app.filesDir, "clm-covers"))
    private val displaySide = (208 * app.resources.displayMetrics.density).roundToInt().coerceIn(384, 768)
    private val titles = object : LruCache<Revision, String>(4 * 1024 * 1024) {
        override fun sizeOf(key: Revision, value: String): Int = 160 + 2 * (key.source.length + value.length)
    }
    private val covers = object : LruCache<Request, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: Request, value: Bitmap): Int = value.allocationByteCount
    }
    private val failed = LruCache<Request, Long>(256)
    private val pending = LinkedHashMap<Request, LibraryItem>()
    private val observers = LinkedHashSet<Runnable>()
    private var active: Request? = null
    private var deliveryQueued = false

    fun addObserver(observer: Runnable) { observers.add(observer) }
    fun removeObserver(observer: Runnable) { observers.remove(observer) }

    fun preview(item: LibraryItem): Preview = preview(item, displaySide)
    fun smallPreview(item: LibraryItem): Preview = preview(item, SUMMARY_SIDE)

    private fun preview(item: LibraryItem, side: Int): Preview {
        val revision = revision(item)
        val cover = covers.get(Request(revision, side)) ?: if (side == SUMMARY_SIDE) {
            covers.get(Request(revision, displaySide))
        } else null
        val title = titles.get(revision)?.takeIf(String::isNotBlank) ?: item.name.removeClmSuffix()
        return Preview(title, cover)
    }

    fun retryPreviews() {
        failed.evictAll()
        notifySoon()
    }

    fun requestPreview(item: LibraryItem) = request(item, displaySide)
    fun requestSmallPreview(item: LibraryItem) = request(item, SUMMARY_SIDE)

    private fun request(item: LibraryItem, side: Int) {
        val key = Request(revision(item), side)
        if (key == active || pending.containsKey(key) || isAvailable(key) || pending.size >= 96) return
        pending[key] = item
        takeNext()
    }

    private fun isAvailable(key: Request): Boolean =
        covers.get(key) != null || (key.side == SUMMARY_SIDE && covers.get(Request(key.revision, displaySide)) != null) ||
            (failed.get(key) ?: 0) > SystemClock.elapsedRealtime()

    fun remember(item: LibraryItem, title: String, encoded: ByteArray) {
        val revision = revision(item)
        val titleChanged = title.isNotBlank() && titles.put(revision, title) != title
        val failedDisplay = failed.remove(Request(revision, displaySide))
        val failedSummary = failed.remove(Request(revision, SUMMARY_SIDE))
        io.execute { disk.write(item, ClmCoverStore.Artwork(title, encoded)) }
        if (titleChanged || failedDisplay != null || failedSummary != null) notifySoon()
    }

    fun seedTitle(item: LibraryItem, title: String) {
        val revision = revision(item)
        if (title.isNotBlank() && titles.get(revision) == null) {
            titles.put(revision, title)
            notifySoon()
        }
    }

    private fun takeNext() {
        if (active != null) return
        while (pending.isNotEmpty()) {
            val (key, item) = pending.entries.first()
            pending.remove(key)
            if (isAvailable(key)) continue
            active = key
            io.execute {
                val result = runCatching { load(item, key.side) }.getOrNull()
                main.post {
                    if (result?.title?.isNotBlank() == true) titles.put(key.revision, result.title)
                    if (result?.cover != null) covers.put(key, result.cover)
                    else failed.put(key, SystemClock.elapsedRealtime() + 60_000)
                    active = null
                    notifySoon()
                    takeNext()
                }
            }
            return
        }
    }

    private fun load(item: LibraryItem, side: Int): Preview {
        val saved = disk.read(item)
        if (saved != null) {
            val bitmap = CoverDecoder.decode(saved.image, side)?.apply { prepareToDraw() }
            if (bitmap != null) return Preview(saved.title, bitmap)
        }
        return ClmArchiveReader.read(app, item.uri, CancellationSignal()).use { archive ->
            val bitmap = CoverDecoder.decode(archive.cover, side)?.apply { prepareToDraw() }
            if (bitmap != null) disk.write(item, ClmCoverStore.Artwork(archive.title, archive.cover), replace = saved != null)
            Preview(archive.title, bitmap)
        }
    }

    private fun revision(item: LibraryItem): Revision =
        Revision(LibraryScanner.sourceId(item.uri), item.bytes, item.modified)

    private fun String.removeClmSuffix(): String = if (endsWith(".clm", ignoreCase = true)) dropLast(4) else this

    private fun notifySoon() {
        if (deliveryQueued) return
        deliveryQueued = true
        main.postDelayed({
            deliveryQueued = false
            observers.toList().forEach(Runnable::run)
        }, 60)
    }

    internal companion object {
        const val NOTIFICATION_SIDE = 256
        private const val SUMMARY_SIDE = 192
    }
}
