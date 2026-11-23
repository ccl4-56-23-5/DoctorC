package com.wirelessalien.zipxtract

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.nativeui.BookMigration
import com.wirelessalien.zipxtract.nativeui.FileHandoff
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** URI tests, not a claim about how a third-party gallery renders its own details panel. */
@RunWith(AndroidJUnit4::class)
class FileHandoffRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val handoff get() = FileHandoff(context)
    private val special = "中文 空格+字面%20和%25 😀 #?.zip"
    private val bytes = ("原始文件字节 +%20%25 😀 #?\n" + "x".repeat(1024)).toByteArray(Charsets.UTF_8)
    private lateinit var root: File
    private val mediaRows = ArrayList<Uri>()
    private val importedFolders = ArrayList<File>()

    @Before fun setup() {
        root = File(context.cacheDir, "uri-qa-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.cacheDir.canonicalFile && root.mkdirs())
    }

    @After fun cleanup() {
        mediaRows.forEach { resolver.delete(it, null, null) }
        val imports = File(context.cacheDir, "imports").canonicalFile
        importedFolders.forEach {
            check(it.canonicalFile.parentFile == imports)
            it.deleteRecursively()
        }
        if (::root.isInitialized) {
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile && root.name.startsWith("uri-qa-"))
            root.deleteRecursively()
        }
    }

    private fun displayName(uri: Uri): String = resolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )!!.use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
    }

    private fun read(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun fixture(name: String = special) = File(root, name).apply { writeBytes(bytes) }
    private fun assertReadGrant(intent: Intent) {
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNotNull(intent.clipData)
    }

    @Test fun fileProviderFallbackHasExactDisplayNameAndUnchangedBytes() {
        val file = fixture()
        val item = handoff.outgoing(file)
        assertEquals("${context.packageName}.provider", item.uri.authority)
        assertEquals(special, file.name)
        assertEquals(special, item.displayName)
        assertEquals(special, item.uri.getQueryParameter("displayName"))
        assertEquals(special, displayName(item.uri))
        assertArrayEquals(bytes, read(item.uri))
        assertArrayEquals(bytes, file.readBytes())
        // %XX in the URI is legitimate; a consumer must query metadata instead of printing it raw.
        assertTrue(item.uri.toString().contains("%25"))
        assertTrue(item.uri.toString().contains("%23"))
        assertTrue(item.uri.toString().contains("%3F", ignoreCase = true))
    }

    @Test fun viewAndMultiShareCarryClipDataTitleAndReadOnlyGrantsForEveryUri() {
        val first = fixture()
        val second = fixture("另一个 +%20%25 😀 #?.txt")
        val view = handoff.viewIntent(first)
        assertReadGrant(view)
        assertEquals(special, view.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(view.data, view.clipData!!.getItemAt(0).uri)
        assertArrayEquals(bytes, read(view.data!!))
        val single = handoff.shareIntent(listOf(first))!!
        assertReadGrant(single)
        assertEquals(Intent.ACTION_SEND, single.action)
        assertEquals(single.getParcelableExtra<Uri>(Intent.EXTRA_STREAM), single.clipData!!.getItemAt(0).uri)
        assertEquals(special, single.getStringExtra(Intent.EXTRA_TITLE))
        val multiple = handoff.shareIntent(listOf(first, second))!!
        assertReadGrant(multiple)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, multiple.action)
        val streams = multiple.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(2, streams.size)
        assertEquals(2, multiple.clipData!!.itemCount)
        streams.forEachIndexed { index, uri ->
            assertEquals(uri, multiple.clipData!!.getItemAt(index).uri)
            assertEquals(listOf(first.name, second.name)[index], displayName(uri))
            assertArrayEquals(bytes, read(uri))
        }
        assertNull(handoff.shareIntent(emptyList()))
    }

    @Test fun incomingMetadataWinsAndIsSanitizedWithoutDecodingPercentOrPlus() {
        val source = fixture("transport-name.bin")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source,
            "../../folder\\$special\u0000\r\n")
        assertEquals(special, handoff.incomingName(uri))
        val imported = handoff.importFile(uri)
        importedFolders += imported.parentFile!!
        assertEquals(special, imported.name)
        assertEquals(File(context.cacheDir, "imports").canonicalFile, imported.parentFile!!.parentFile)
        assertArrayEquals(bytes, imported.readBytes())
        assertEquals("transport-name.bin", source.name)
        assertArrayEquals(bytes, source.readBytes())
        val dotUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", source, "..")
        assertEquals(source.name, handoff.incomingName(dotUri))
    }

    @Test fun unavailableMetadataFallsBackToAndroidDecodedSegmentExactlyOnce() {
        val uri = Uri.Builder().scheme("content").authority("${context.packageName}.missing-uri-fixture")
            .appendPath(special).build()
        assertEquals(special, uri.lastPathSegment)
        assertEquals(special, handoff.incomingName(uri))
        val literal = Uri.Builder().scheme("content").authority("${context.packageName}.missing-uri-fixture")
            .appendPath("%2e%2e%2f+literal%20%25.zip").build()
        assertEquals("%2e%2e%2f+literal%20%25.zip", handoff.incomingName(literal))
    }

    private fun migrationIntent(uri: Uri) = Intent(BookMigration.SEND_ACTION).apply {
        setPackage(context.packageName)
        type = BookMigration.MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("BookDoctorC migration", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun migrationRouteStagesOpaqueBytesAndUsesReadOnlyRestoreHandoff() {
        val source = fixture("BookDoctorC 音乐与记录 +%20.bdcm")
        val uri = handoff.outgoing(source, preferMediaStore = false).uri
        val request = migrationIntent(uri)
        assertNotNull(request.resolveActivity(context.packageManager))
        val staged = BookMigration.stage(context, request) {}
        try {
            assertEquals(source.name, staged.name)
            assertNotEquals(source.canonicalPath, staged.canonicalPath)
            assertArrayEquals(source.readBytes(), staged.readBytes())
            val restore = BookMigration.viewIntent(context, staged)
            assertEquals(Intent.ACTION_VIEW, restore.action)
            assertEquals(BookMigration.BOOK_PACKAGE, restore.`package`)
            assertEquals(BookMigration.MIME, restore.type)
            assertReadGrant(restore)
            assertEquals(restore.data, restore.clipData!!.getItemAt(0).uri)
            assertEquals(source.name, displayName(restore.data!!))
            assertArrayEquals(bytes, read(restore.data!!))
        } finally {
            BookMigration.discard(staged)
        }
        assertFalse(staged.exists())
        assertFalse(staged.parentFile!!.exists())
        assertTrue(source.exists())
    }

    @Test fun migrationRequiresOneMatchingContentUriWithReadGrantAndPackageName() {
        val source = fixture("correct.bdcm")
        val uri = handoff.outgoing(source, preferMediaStore = false).uri
        val invalidName = handoff.outgoing(fixture("ordinary.zip"), preferMediaStore = false).uri
        val requests = listOf(
            migrationIntent(uri).apply { type = "application/zip" },
            migrationIntent(uri).apply { flags = 0 },
            migrationIntent(uri).apply { addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION) },
            migrationIntent(uri).apply { clipData = ClipData.newRawUri("different", invalidName) },
            migrationIntent(uri).apply { clipData!!.addItem(ClipData.Item(uri)) },
            migrationIntent(Uri.fromFile(source)),
            migrationIntent(invalidName)
        )
        requests.forEach { request ->
            assertTrue(runCatching { BookMigration.stage(context, request) {} }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(source.exists())
    }

    @Suppress("DEPRECATION")
    @Test fun exactExistingMediaStoreFileUsesMediaUriAndSameNameElsewhereDoesNot() {
        assumeTrue("Scoped-storage fixture requires Android 10+", Build.VERSION.SDK_INT >= 29)
        val token = UUID.randomUUID().toString()
        // MediaProvider itself may sanitize FAT-invalid '?'; full #? fidelity is tested above with FileProvider.
        val requestedName = "中文 空格+%20%25 😀 #-$token.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, requestedName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZipDoctorC-UriQA-$token")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val indexedUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        mediaRows += indexedUri
        val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=", Base64.DEFAULT)
        resolver.openOutputStream(indexedUri, "w")!!.use { it.write(png) }
        resolver.update(indexedUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val data = resolver.query(indexedUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
        }
        val file = File(data).canonicalFile
        assertTrue(file.isFile)
        val media = handoff.outgoing(file)
        assertEquals("media", media.uri.authority)
        assertEquals(file.name, displayName(media.uri))
        assertArrayEquals(png, read(media.uri))
        assertArrayEquals(png, file.readBytes())
        assertEquals(media.uri, handoff.viewIntent(file).data)
        assertEquals(media.uri, handoff.shareIntent(listOf(file))!!.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val forcedFallback = handoff.outgoing(file, preferMediaStore = false)
        assertEquals("${context.packageName}.provider", forcedFallback.uri.authority)
        assertEquals(file.name, displayName(forcedFallback.uri))
        assertArrayEquals(png, read(forcedFallback.uri))
        val sameNameElsewhere = fixture(file.name)
        assertNull(handoff.existingMediaUri(sameNameElsewhere))
        val unindexed = handoff.outgoing(sameNameElsewhere)
        assertEquals("${context.packageName}.provider", unindexed.uri.authority)
        assertArrayEquals(bytes, read(unindexed.uri))
        assertNotEquals(file.canonicalPath, sameNameElsewhere.canonicalPath)
    }
}
