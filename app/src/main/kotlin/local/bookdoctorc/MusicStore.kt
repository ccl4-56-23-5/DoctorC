package local.bookdoctorc

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import com.example.clmplayer.LibraryScanner
import java.io.IOException
import java.text.Collator
import java.util.Locale

internal object MusicStore {
    data class Revision(val key: String, val bytes: Long, val modified: Long, val name: String, val folder: String)

    data class Song(
        val uri: Uri, val name: String, val folder: String, val folderId: String,
        val bytes: Long, val modified: Long, val added: Long, val plays: Int,
        val lastPlayed: Long, val position: Long
    ) {
        val key: String get() = uri.toString()
        val revision = Revision(key, bytes, modified, name, folder)
        val displayName: String get() = name.substringBeforeLast('.', name)
    }

    data class Playlist(val id: String, val title: String, val path: String, val songs: List<Song>)

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE music_items (
            uri TEXT PRIMARY KEY, name TEXT NOT NULL, folder TEXT NOT NULL, folder_id TEXT NOT NULL,
            bytes INTEGER NOT NULL, modified INTEGER NOT NULL, added INTEGER NOT NULL,
            plays INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0,
            position INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1,
            imported INTEGER NOT NULL DEFAULT 0)""")
    }

    fun createTimes(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE music_time (day TEXT NOT NULL, uri TEXT NOT NULL,
            ms INTEGER NOT NULL, PRIMARY KEY(day,uri))""")
    }

    fun addTime(db: SQLiteDatabase, day: String, uri: String, ms: Long) {
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO music_time(day,uri,ms) VALUES(?,?,0)", arrayOf(day, uri))
            db.execSQL("UPDATE music_time SET ms=ms+? WHERE day=? AND uri=?", arrayOf<Any>(ms, day, uri))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private val extensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "amr", "mp4", "webm", "mka")
    fun isMusic(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in extensions

    fun scan(context: Context, db: SQLiteDatabase, directory: Uri, signal: CancellationSignal): List<Song> {
        val files = LibraryScanner.scan(context, directory, signal, ::isMusic)
        signal.throwIfCanceled()
        val added = System.currentTimeMillis()
        val treeName = runCatching {
            val root = android.provider.DocumentsContract.getTreeDocumentId(directory)
            SourceFiles(context).displayName(android.provider.DocumentsContract.buildDocumentUriUsingTree(directory, root))
        }.getOrDefault("根目录")
        db.beginTransaction()
        try {
            db.execSQL("UPDATE music_items SET active=0 WHERE imported=0 AND active=1")
            for (file in files) {
                signal.throwIfCanceled()
                val uri = file.uri.toString()
                val values = ContentValues().apply {
                    put("uri", uri)
                    put("name", file.name)
                    put("folder", if (file.folder == "根目录") treeName else file.folder)
                    put("folder_id", directory.authority + ":" + file.folderId)
                    put("bytes", file.bytes)
                    put("modified", file.modified)
                    put("active", 1)
                }
                if (db.update("music_items", values, "uri=?", arrayOf(uri)) == 0) {
                    values.put("added", added)
                    db.insertOrThrow("music_items", null, values)
                }
            }
            signal.throwIfCanceled()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return songs(db)
    }

    fun songs(db: SQLiteDatabase, activeOnly: Boolean = true): List<Song> = buildList {
        db.query("music_items", null, if (activeOnly) "active=1" else null,
            null, null, null, "folder COLLATE NOCASE, folder_id, name COLLATE NOCASE").use { cursor ->
            while (cursor.moveToNext()) add(read(cursor))
        }
    }

    private fun read(cursor: Cursor) = Song(
        Uri.parse(cursor.getString(0)), cursor.getString(1), cursor.getString(2), cursor.getString(3),
        cursor.getLong(4), cursor.getLong(5), cursor.getLong(6), cursor.getInt(7), cursor.getLong(8), cursor.getLong(9)
    )

    fun playlists(songs: List<Song>, byAdded: Boolean, descending: Boolean): List<Playlist> {
        val collator = Collator.getInstance(Locale.CHINA)
        val byName = Comparator<Song> { a, b ->
            collator.compare(a.name, b.name).takeUnless { it == 0 } ?: a.key.compareTo(b.key)
        }
        val order = if (byAdded) compareBy<Song> { it.added }.then(byName) else byName
        return songs.groupBy { it.folderId }.map { (id, members) ->
            val path = members.first().folder
            Playlist(id, path.substringAfterLast('/'), path, members.sortedWith(if (descending) order.reversed() else order))
        }.sortedWith { a, b -> collator.compare(a.path, b.path).takeUnless { it == 0 } ?: a.id.compareTo(b.id) }
    }

    fun record(db: SQLiteDatabase, uri: String, started: Boolean, position: Long) {
        db.execSQL("""UPDATE music_items SET position=?, plays=plays+?, last_played=CASE WHEN ?=1 THEN ?
            ELSE last_played END WHERE uri=?""",
            arrayOf<Any>(position.coerceAtLeast(0), if (started) 1 else 0, if (started) 1 else 0, System.currentTimeMillis(), uri))
    }

    fun fileUri(context: Context, file: java.io.File): Uri =
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun readable(context: Context, uri: Uri): Boolean = try {
        SourceFiles(context).descriptor(uri).use { true }
    } catch (_: IOException) { false } catch (_: SecurityException) { false }
}
