package local.bookdoctorc

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import com.example.clmplayer.LibraryItem
import com.example.clmplayer.LibraryScanner
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal class ModuleTransfer(private val context: Context, private val library: Library,
                              private val files: SourceFiles) {
    private data class Source(val uri: Uri, val path: String)
    data class Outcome(val count: Int, val alreadyRestored: Boolean)
    private val reader = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val clm = context.getSharedPreferences("clm_player", Context.MODE_PRIVATE)
    private val transferDir get() = File(context.cacheDir, "module-transfers")

    fun pack(mask: Int, podcastTree: Uri?, musicTree: Uri?, openPodcasts: List<LibraryItem>): File {
        require(mask and ALL == mask && mask != 0) { "请选择要迁移的模块" }
        val database = library.writableDatabase
        if (mask and MUSIC != 0 && musicTree != null)
            MusicStore.scan(context, database, musicTree, CancellationSignal())
        val records = JSONObject().put("format", "BookDoctorC").put("schema", 1).put("id", UUID.randomUUID().toString())
            .put("modules", mask)
        val sources = mutableListOf<Source>()
        val used = HashSet<String>()
        val bookCovers = mutableListOf<Pair<Long, File>>()

        if (mask and BOOKS != 0) {
            val layout = JSONObject()
            database.beginTransactionNonExclusive()
            try {
                for (table in bookTables) layout.put(table, LocalBackup.rows(database, table))
                for (book in library.allBooks()) {
                    val cover = library.cover(book)
                    if (cover.isFile) bookCovers += book.id() to cover
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
            val paths = HashMap<String, String>()
            for (row in objects(layout.getJSONArray("books"))) {
                val uri = row.getString("uri")
                val path = paths.getOrPut(uri) {
                    allocate("books", "", row.getString("name"), used).also { sources += Source(Uri.parse(uri), it) }
                }
                row.put("payload", path)
            }
            records.put("books", layout)
        }

        if (mask and PODCAST != 0) {
            val rows = JSONArray()
            val candidates = (podcasts(database) + (podcastTree?.let {
                LibraryScanner.scan(context, it, CancellationSignal())
            } ?: emptyList()) + openPodcasts.filter { it.name.endsWith(".clm", true) && MusicStore.readable(context, it.uri) }).distinctBy { LibraryScanner.sourceId(it.uri) }
            for (item in candidates) {
                val path = allocate("podcast", item.folder, item.name, used)
                sources += Source(item.uri, path)
                rows.put(JSONObject().put("payload", path).put("uri", item.uri.toString()).put("name", item.name)
                    .put("folder", item.folder).put("folder_id", item.folderId).put("modified", item.modified))
            }
            val progress = JSONObject()
            database.query("migration_clm_resume", null, null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) progress.put(cursor.getString(0), cursor.getString(1))
            }
            for ((key, value) in clm.all) {
                if (key.startsWith("resume:") && value is String) progress.put(key.removePrefix("resume:"), value)
            }
            val recents = runCatching { JSONArray(clm.getString("recent_albums", "[]") ?: "[]") }.getOrDefault(JSONArray())
            records.put("podcast", JSONObject().put("items", rows)
                .put("listening_time", LocalBackup.rows(database, "listening_time")).put("recents", recents)
                .put("progress", progress))
        }

        if (mask and MUSIC != 0) {
            val rows = JSONArray()
            val musicPaths = HashMap<String, String>()
            for (song in MusicStore.songs(database)) {
                val path = allocate("music", song.folder, song.name, used)
                sources += Source(song.uri, path)
                musicPaths[song.key] = path
                rows.put(JSONObject().put("payload", path).put("name", song.name).put("folder", song.folder)
                    .put("folder_id", song.folderId).put("modified", song.modified)
                    .put("added", song.added).put("plays", song.plays)
                    .put("last_played", song.lastPlayed).put("position", song.position))
            }
            val times = JSONArray()
            database.query("music_time", null, null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val path = musicPaths[cursor.getString(1)] ?: continue
                    times.put(JSONObject().put("day", cursor.getString(0)).put("payload", path)
                        .put("ms", cursor.getLong(2)))
                }
            }
            records.put("music", rows).put("music_time", times)
        }

        if (!transferDir.isDirectory && !transferDir.mkdirs()) throw IOException("无法创建迁移暂存目录")
        pruneDrafts(context, null)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val draft = File.createTempFile("BookDoctorC-", ".part", transferDir)
        val result = File(transferDir, "MookDoctorC-迁移-$stamp-" + records.getString("id").take(8) + ".bdcm")
        try {
            ZipOutputStream(BufferedOutputStream(draft.outputStream())).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                val manifest = JSONArray()
                for (source in sources) {
                    zip.putNextEntry(ZipEntry(source.path))
                    val (size, hash) = try { files.read(source.uri).use { copy(it, zip) } }
                    catch (failure: Exception) {
                        throw IOException("无法读取 ${source.path.substringAfterLast('/')}，请检查文件和目录授权", failure)
                    }
                    zip.closeEntry()
                    manifest.put(JSONObject().put("path", source.path).put("bytes", size).put("sha256", hash))
                }
                for ((id, cover) in bookCovers) {
                    val path = "covers/$id.jpg"
                    if (cover.length() > 3L * 1024 * 1024) continue
                    zip.putNextEntry(ZipEntry(path))
                    val (size, hash) = cover.inputStream().use { copy(it, zip) }
                    zip.closeEntry()
                    manifest.put(JSONObject().put("path", path).put("bytes", size).put("sha256", hash))
                }
                val data = records.put("payloads", manifest).toString().toByteArray(Charsets.UTF_8)
                if (data.size > META_LIMIT) throw IOException("迁移记录过大")
                zip.putNextEntry(ZipEntry(META))
                zip.write(data)
                zip.closeEntry()
            }
            if (!draft.renameTo(result)) throw IOException("无法保存迁移文件")
            return result
        } finally {
            if (draft.exists()) draft.delete()
        }
    }

    fun sendingIntent(file: File): Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".transfer", file)
        return Intent(SEND).setPackage(FILEDOC).setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri(file.name, uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun restore(uri: Uri): Outcome {
        require(uri.scheme == "content") { "请选择共享的迁移文件" }
        val root = File(context.filesDir, "module-files")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建迁移文件目录")
        val restoreDir = File(transferDir, "restore")
        if (!restoreDir.isDirectory && !restoreDir.mkdirs()) throw IOException("无法创建恢复暂存目录")
        val draft = File.createTempFile("restore-", ".part", restoreDir)
        var staged: File? = null
        var published: File? = null
        var imported = false
        try {
            files.read(uri).use { input -> draft.outputStream().buffered().use { copy(input, it) } }
            ZipFile(draft).use { zip ->
                val meta = zip.getEntry(META) ?: throw IOException("缺少 MookDoctorC 迁移记录")
                val data = zip.getInputStream(meta).use { JSONObject(String(SourceFiles.bytes(it, META_LIMIT), Charsets.UTF_8)) }
                require(data.getString("format") == "BookDoctorC" && data.getInt("schema") == 1) { "迁移文件版本不受支持" }
                val id = UUID.fromString(data.getString("id")).toString()
                require(id == data.getString("id")) { "迁移编号无效" }
                val mask = data.getInt("modules")
                require(mask != 0 && mask and ALL == mask)
                val database = library.writableDatabase
                if (restored(database, id)) return Outcome(0, true)

                val expected = LinkedHashMap<String, JSONObject>()
                for (row in objects(data.getJSONArray("payloads"))) {
                    val path = row.getString("path")
                    val authorized = when {
                        path.startsWith("files/books/") || path.matches(Regex("covers/[0-9]+\\.jpg")) -> mask and BOOKS != 0
                        path.startsWith("files/podcast/") -> mask and PODCAST != 0
                        path.startsWith("files/music/") -> mask and MUSIC != 0
                        else -> false
                    }
                    require(safePath(path) && authorized) { "迁移项目超出所选模块" }
                    require(expected.put(path, row) == null) { "迁移文件含重复路径" }
                }
                val names = HashSet<String>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val item = enumeration.nextElement()
                    require(!item.isDirectory && (item.name == META || item.name in expected) && names.add(item.name)) {
                        "迁移文件含无法识别的项目"
                    }
                }
                require(names.size == expected.size + 1) { "迁移文件缺少源文件" }

                val temp = File(root, ".$id-" + UUID.randomUUID())
                check(temp.mkdir()) { "无法暂存迁移文件" }
                staged = temp
                val base = File(root, id)
                val resolved = HashMap<String, Pair<Uri, Long>>()
                for ((path, row) in expected) {
                    if (Thread.currentThread().isInterrupted) throw IOException("迁移已取消")
                    val entry = zip.getEntry(path)
                    val size = row.getLong("bytes")
                    require(size >= 0 && entry.size == size && row.getString("sha256").matches(Regex("[0-9a-f]{64}"))) {
                        "源文件大小或校验值无效"
                    }
                    val target = File(temp, path)
                    if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) throw IOException("无法创建恢复目录")
                    val actual = zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { output -> copy(input, output, size) }
                    }
                    require(actual.first == size && actual.second == row.getString("sha256")) { "源文件校验失败" }
                    if (path.startsWith("files/")) resolved[path] = MusicStore.fileUri(context, File(base, path)) to size
                }
                if (base.exists()) {
                    if (restored(database, id)) return Outcome(0, true)
                    check(base.deleteRecursively()) { "请清理未完成的上次导入" }
                }
                if (!temp.renameTo(base)) throw IOException("无法发布恢复文件")
                staged = null
                published = base
                val mappedBooks: Map<Long, Long>
                val podcastAliases = HashMap<String, String>()
                database.beginTransaction()
                try {
                    mappedBooks = if (mask and BOOKS != 0) mergeBooks(data.getJSONObject("books"), resolved) else emptyMap()
                    if (mask and PODCAST != 0) mergePodcasts(database, data.getJSONObject("podcast"), resolved, id, podcastAliases)
                    if (mask and MUSIC != 0) mergeMusic(database, data.getJSONArray("music"), data.optJSONArray("music_time"), resolved, id)
                    database.execSQL("INSERT INTO restored_modules(id) VALUES(?)", arrayOf(id))
                    database.setTransactionSuccessful()
                } finally { database.endTransaction() }
                imported = true
                for ((original, target) in mappedBooks) {
                    val from = File(base, "covers/$original.jpg")
                    if (!from.isFile) continue
                    runCatching {
                        val book = library.book(target) ?: return@runCatching
                        val to = library.cover(book)
                        if (!to.exists()) from.inputStream().use { input ->
                            val bitmap = SourceFiles.bitmap(SourceFiles.bytes(input, 3 * 1024 * 1024), 1024)
                            if (bitmap != null) try { SourceFiles.storeCover(to, bitmap) } finally { bitmap.recycle() }
                        }
                    }
                }
                if (mask and PODCAST != 0) runCatching {
                    mergeRecents(data.getJSONObject("podcast").optJSONArray("recents") ?: JSONArray(), podcastAliases)
                }
                return Outcome(resolved.size, false)
            }
        } finally {
            staged?.deleteRecursively()
            if (!imported) published?.deleteRecursively()
            draft.delete()
        }
    }

    private fun mergeBooks(data: JSONObject, paths: Map<String, Pair<Uri, Long>>): Map<Long, Long> {
        val mapping = HashMap<String, String>()
        for (row in objects(data.getJSONArray("books"))) {
            val old = row.getString("uri")
            val new = payload(row, paths, "books").first.toString()
            mapping[old] = new
            row.put("uri", new)
        }
        remap(data.getJSONArray("reading_time"), mapping)
        return LocalBackup(context, library, files, reader).merge(data)
    }

    private fun mergePodcasts(db: SQLiteDatabase, data: JSONObject, paths: Map<String, Pair<Uri, Long>>,
                              id: String, aliases: MutableMap<String, String>) {
        for (row in objects(data.getJSONArray("items"))) {
            val (uri, size) = payload(row, paths, "podcast")
            val original = row.getString("uri")
            aliases[original] = uri.toString()
            db.insertOrThrow("imported_podcasts", null, ContentValues().apply {
                put("uri", uri.toString())
                put("name", row.getString("name"))
                put("folder", row.getString("folder"))
                put("folder_id", id + ":" + row.getString("folder_id"))
                put("bytes", size)
                put("modified", row.getLong("modified"))
            })
        }
        val listening = data.getJSONArray("listening_time")
        remap(listening, aliases)
        for (row in objects(listening)) {
            val day = java.time.LocalDate.parse(row.getString("day")).toString()
            val album = row.getString("album")
            require(album.matches(Regex("[0-9a-f]{32}"))) { "播客记录编号无效" }
            db.execSQL("INSERT OR IGNORE INTO listening_time(day,album,uri,title,ms) VALUES(?,?,?,?,0)",
                arrayOf(day, album, row.getString("uri"), row.getString("title")))
            db.execSQL("UPDATE listening_time SET ms=MAX(ms,?),uri=? WHERE day=? AND album=?",
                arrayOf(row.getLong("ms").coerceAtLeast(0), row.getString("uri"), day, album))
        }
        val saved = data.getJSONObject("progress")
        for (key in saved.keys()) {
            val progress = saved.getString(key)
            if (key.matches(Regex("[0-9a-f]{32}")) && progress.matches(Regex("[0-9]{1,6}\\|[0-9]{1,18}\\|(saved|fresh)"))) {
                db.execSQL("INSERT OR IGNORE INTO migration_clm_resume(album,progress) VALUES(?,?)", arrayOf(key, progress))
            }
        }
    }

    private fun mergeMusic(db: SQLiteDatabase, rows: JSONArray, times: JSONArray?,
                           paths: Map<String, Pair<Uri, Long>>, id: String) {
        val aliases = HashMap<String, String>()
        for (row in objects(rows)) {
            val (uri, size) = payload(row, paths, "music")
            val name = row.getString("name")
            require(MusicStore.isMusic(name)) { "音乐文件类型无效" }
            aliases[row.getString("payload")] = uri.toString()
            db.insertOrThrow("music_items", null, ContentValues().apply {
                put("uri", uri.toString())
                put("name", name)
                put("folder", row.getString("folder"))
                put("folder_id", "$id:" + row.getString("folder_id"))
                put("bytes", size)
                put("modified", row.getLong("modified"))
                put("added", row.getLong("added").coerceAtLeast(0))
                put("position", row.getLong("position").coerceAtLeast(0))
                put("plays", row.getInt("plays").coerceAtLeast(0))
                put("last_played", row.getLong("last_played").coerceAtLeast(0))
                put("imported", 1)
            })
        }
        for (row in objects(times ?: JSONArray())) {
            val uri = aliases[row.getString("payload")] ?: throw IOException("找不到音乐记录对应的文件")
            val day = java.time.LocalDate.parse(row.getString("day")).toString()
            val ms = row.getLong("ms")
            require(ms in 1..93_600_000L) { "音乐播放时间无效" }
            db.execSQL("INSERT INTO music_time(day,uri,ms) VALUES(?,?,?)", arrayOf<Any>(day, uri, ms))
        }
    }

    private fun mergeRecents(source: JSONArray, aliases: Map<String, String>) {
        remap(source, aliases)
        val known = HashSet<String>()
        val combined = JSONArray()
        val original = runCatching { JSONArray(clm.getString("recent_albums", "[]") ?: "[]") }.getOrDefault(JSONArray())
        for (row in objects(original) + objects(source)) {
            val id = row.optString("id")
            if (combined.length() >= 64) break
            if (id.isNotEmpty() && known.add(id)) combined.put(row)
        }
        clm.edit().putString("recent_albums", combined.toString()).apply()
    }

    private fun remap(rows: JSONArray, mapping: Map<String, String>) {
        for (row in objects(rows)) mapping[row.optString("uri")]?.let { row.put("uri", it) }
    }

    private fun payload(row: JSONObject, paths: Map<String, Pair<Uri, Long>>, module: String): Pair<Uri, Long> {
        val path = row.getString("payload")
        require(path.startsWith("files/$module/")) { "迁移项目所属模块无效" }
        return paths[path] ?: throw IOException("迁移项目缺少源文件")
    }

    private fun restored(db: SQLiteDatabase, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM restored_modules WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    private fun allocate(module: String, folder: String, filename: String, used: MutableSet<String>): String {
        val folders = folder.split('/').filter { it.isNotBlank() }.map(::clean)
        val home = (listOf("files", module) + folders).joinToString("/")
        val name = clean(filename)
        var result = "$home/$name"
        var count = 2
        while (!used.add(result)) result = "$home/" + count++ + "-" + name
        require(safePath(result)) { "源文件路径太长" }
        return result
    }

    private fun clean(name: String): String {
        val value = name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").trim().take(75).trimEnd('.')
        return if (value.isEmpty() || value == "..") "文件" else value
    }

    private fun safePath(path: String): Boolean = path.length in 1..1800 && !path.contains('\\') &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl) }

    private fun copy(input: InputStream, output: OutputStream, limit: Long = Long.MAX_VALUE): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var length = 0L
        val source = BufferedInputStream(input, buffer.size)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw IOException("迁移已取消")
            val size = source.read(buffer)
            if (size < 0) break
            if (size > limit - length) throw IOException("迁移源文件超出声明大小")
            length += size
            digest.update(buffer, 0, size)
            output.write(buffer, 0, size)
        }
        return length to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object {
        private var recovered = false

        @JvmStatic @Synchronized fun recoverDrafts(context: Context, keep: String?) {
            if (recovered) return
            recovered = true
            val cache = File(context.cacheDir, "module-transfers")
            cache.listFiles()?.filter { it.isFile && it.name != keep }?.forEach(File::delete)
            File(cache, "restore").listFiles()?.filter { it.isFile }?.forEach(File::delete)
            val stages = Regex("""\.[0-9a-f-]{36}-[0-9a-f-]{36}""")
            File(context.filesDir, "module-files").listFiles()
                ?.filter { it.isDirectory && stages.matches(it.name) }
                ?.forEach { it.deleteRecursively() }
        }

        private fun pruneDrafts(context: Context, keep: String?) {
            val cutoff = System.currentTimeMillis() - 86_400_000L
            File(context.cacheDir, "module-transfers").listFiles()
                ?.filter { it.isFile && it.name != keep && it.lastModified() < cutoff }
                ?.forEach(File::delete)
        }

        const val BOOKS = 1
        const val PODCAST = 2
        const val MUSIC = 4
        const val ALL = BOOKS or PODCAST or MUSIC
        const val FILEDOC = "local.sevenzip.android"
        const val MIME = "application/vnd.bookdoctorc.migration+zip"
        const val SEND = "local.sevenzip.android.action.SEND_BOOKDOCTOR_MIGRATION"
        private const val META = "bookdoctorc-migration.json"
        private const val META_LIMIT = 32 * 1024 * 1024
        private val bookTables = arrayOf("boxes", "books", "bookmarks", "reading_time", "replace_rules")
        private fun objects(rows: JSONArray): List<JSONObject> = (0 until rows.length()).map(rows::getJSONObject)

        @JvmStatic fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE restored_modules(id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE migration_clm_resume(album TEXT PRIMARY KEY,progress TEXT NOT NULL)")
            db.execSQL("""CREATE TABLE imported_podcasts(uri TEXT PRIMARY KEY,name TEXT NOT NULL,
                folder TEXT NOT NULL,folder_id TEXT NOT NULL,bytes INTEGER NOT NULL,modified INTEGER NOT NULL)""")
        }

        @JvmStatic fun podcasts(db: SQLiteDatabase): List<LibraryItem> = buildList {
            db.query("imported_podcasts", null, null, null, null, null,
                "folder COLLATE NOCASE,name COLLATE NOCASE").use { cursor ->
                while (cursor.moveToNext()) add(LibraryItem(Uri.parse(cursor.getString(0)), cursor.getString(1),
                    cursor.getString(2), cursor.getLong(4), cursor.getLong(5), cursor.getString(3)))
            }
        }

        fun resume(db: SQLiteDatabase, id: String): String? =
            db.rawQuery("SELECT progress FROM migration_clm_resume WHERE album=?", arrayOf(id))
                .use { if (it.moveToFirst()) it.getString(0) else null }
    }
}
