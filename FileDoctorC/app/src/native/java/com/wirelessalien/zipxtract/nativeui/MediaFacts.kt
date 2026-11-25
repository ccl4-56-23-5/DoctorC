package com.wirelessalien.zipxtract.nativeui

import android.media.MediaMetadataRetriever
import android.content.Context
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.wirelessalien.zipxtract.helper.MimeTypeHelper
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object MediaFacts {
    private data class Value(val modified:Long,val bytes:Long,val duration:Long)
    private val cache=ConcurrentHashMap<String,Value>()
    private val readers=Semaphore(2)
    private data class Capture(val modified: Long, val bytes: Long, val time: Long)
    private val captureCache = ConcurrentHashMap<String, Capture>()

    /** Read original photo EXIF or the date exposed by the video container on the IO worker. */
    fun captureTime(file: File): Long {
        val modified = file.lastModified()
        if (!file.isFile) return modified
        val bytes = file.length()
        captureCache[file.path]?.takeIf { it.modified == modified && it.bytes == bytes }?.let { return it.time }
        val extension = file.extension.lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty()
        val time = when {
            mime.startsWith("video/") || extension in MimeTypeHelper.videoExtensions || extension == "m4v" ->
                videoDate(file)
            else -> exifCaptureDate(file)?.millis
        } ?: modified
        if (captureCache.size >= 2048) captureCache.clear()
        captureCache[file.path] = Capture(modified, bytes, time)
        return time
    }

    private val exifExtensions = setOf("arw", "cr2", "dng", "heic", "heif", "nef", "nrw", "orf", "pef",
        "raf", "rw2", "srw", "tif", "tiff", "webp")

    private val exifOffset = Regex("[+-](?:0[0-9]|1[0-4]):[0-5][0-9]")

    fun isExifPhotoCandidate(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.ROOT)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension).orEmpty()
        return mime.startsWith("image/") || extension in exifExtensions
    }

    /** Original photo timestamp. Call on the IO worker. */
    fun exifCaptureDate(file: File): RenamerRules.CaptureDate? =
        if (isExifPhotoCandidate(file)) runCatching { photoDate(file) }.getOrNull() else null

    private fun photoDate(file: File): RenamerRules.CaptureDate? {
        val exif = ExifInterface(file)
        val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.trim() ?: return null
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.trim()
        // A photo without an EXIF offset is a local wall-clock time.
        val zone = if (offset != null && exifOffset.matches(offset)) TimeZone.getTimeZone("GMT$offset")
            else TimeZone.getDefault()
        val time = parseDate(date, "yyyy:MM:dd HH:mm:ss", zone)
            ?: parseDate(date, "yyyy-MM-dd HH:mm:ss", zone) ?: return null
        val millis = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)?.trim()
            ?.take(3)?.padEnd(3, '0')?.toLongOrNull() ?: 0L
        return RenamerRules.CaptureDate(time + millis, zone)
    }

    private fun parseDate(raw: String, pattern: String, zone: TimeZone): Long? {
        val format = SimpleDateFormat(pattern, Locale.ROOT).apply {
            isLenient = false
            timeZone = zone
        }
        val position = ParsePosition(0)
        val date = format.parse(raw, position)
        return date?.time?.takeIf { position.index == raw.length }
    }

    private fun videoDate(file: File): Long? {
        val reader = MediaMetadataRetriever()
        val raw = try {
            reader.setDataSource(file.path)
            reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { reader.release() }
        }?.trim() ?: return null

        // An MP4 movie header with a zero creation date represents missing metadata.
        if (raw.startsWith("19040101T000000") || raw.startsWith("1904-01-01T00:00:00")) return null
        val patterns = listOf("yyyyMMdd'T'HHmmss.SSSX", "yyyyMMdd'T'HHmmssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")
        val utc = TimeZone.getTimeZone("UTC")
        return patterns.firstNotNullOfOrNull { parseDate(raw, it, utc) }
    }

    fun indexedDurations(context:Context,files:List<File>):Map<String,Long>{
        val paths=files.mapTo(HashSet()){it.path};val found=HashMap<String,Long>()
        runCatching{context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DATA,MediaStore.Video.Media.DURATION),null,null,null)?.use{cursor->
                while(cursor.moveToNext()){val path=cursor.getString(0)?:continue;if(path in paths)found[path]=cursor.getLong(1)}
        }}
        return found
    }
    suspend fun durationAsync(file:File):Long=readers.withPermit{withContext(Dispatchers.IO){duration(file)}}
    /** Call from the file catalog IO dispatcher, never from row binding. */
    fun duration(file:File):Long{
        cache[file.path]?.takeIf{it.modified==file.lastModified()&&it.bytes==file.length()}?.let{return it.duration}
        val reader=MediaMetadataRetriever()
        val result=try{reader.setDataSource(file.path);reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L}
            catch(_:Exception){0L}finally{runCatching{reader.release()}}
        if(cache.size>512)cache.clear()
        cache[file.path]=Value(file.lastModified(),file.length(),result)
        return result
    }
}
