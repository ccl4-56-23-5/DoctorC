package com.wirelessalien.zipxtract

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.nativeui.BatchRenameEngine
import com.wirelessalien.zipxtract.nativeui.MediaStoreRename
import com.wirelessalien.zipxtract.nativeui.RenamerRules
import com.wirelessalien.zipxtract.nativeui.RenamerRules.Kind
import com.wirelessalien.zipxtract.nativeui.RenamerRules.Rule as RenamerRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

/**
 * 1.0.9 requirement: renaming must leave Android's own view of the file untouched.
 *
 * For an indexed image the SAME MediaStore item must keep its _ID, DATE_ADDED, DATE_MODIFIED and
 * DATE_TAKEN, and the filesystem modification time must survive. Only disposable fixtures are
 * created here; nothing writes or back-dates those date columns.
 */
@RunWith(AndroidJUnit4::class)
class Ui109RenameTimeTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val resolver get() = context.contentResolver
    private val created = mutableListOf<Uri>()
    private val files = mutableListOf<File>()
    private lateinit var album: String

    @get:Rule val trace = object : TestWatcher() {
        override fun starting(description: Description) { Log.i("Ui109Time", "ENTER ${description.methodName}") }
        override fun failed(error: Throwable, description: Description) { Log.e("Ui109Time", "FAIL ${description.methodName}", error) }
        override fun finished(description: Description) { Log.i("Ui109Time", "EXIT ${description.methodName}") }
    }

    @Before fun setup() {
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
        )).use { it.readBytes() }
        album = "Pictures/ZipDoctorQA109-${System.nanoTime()}"
        // DATE_ADDED/DATE_TAKEN cannot be written from this test; only read back and compared.
        assertTrue("MediaStore writes need API29+", Build.VERSION.SDK_INT >= 29)
    }

    @After fun cleanup() {
        for (uri in created) runCatching { resolver.delete(uri, null, null) }
        created.clear()
        for (file in files) runCatching { file.delete() }
        files.clear()
    }

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(32, 96, 160))
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    /** Creates a real, provider-owned image row (not a scanner side effect of a raw file). */
    private fun createIndexedImage(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, album)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, 1_504_000_123_456L)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        created += uri
        resolver.openOutputStream(uri)!!.use { it.write(jpegBytes()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private data class Snapshot(val id: Long, val path: String, val name: String,
                                val added: Long?, val modified: Long?, val taken: Long?)

    private fun snapshot(uri: Uri): Snapshot {
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Images.Media.DATE_TAKEN)
        return resolver.query(uri, columns, null, null, null)!!.use { cursor ->
            check(cursor.moveToFirst()) { "media row disappeared: $uri" }
            Snapshot(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                cursor.getLong(3), cursor.getLong(4), cursor.getLong(5))
        }
    }

    private fun rule(suffix: String) = RenamerRule(Kind.INSERT, text = suffix, mode = "suffix", skipExtension = true)

    @Test fun indexedImageKeepsItsRowIdentityAndAllDates() {
        val before = createIndexedImage("qa109-original-${System.nanoTime()}.jpg")
        val clean = snapshot(before)
        val file = File(clean.path)
        files += file
        assertTrue("fixture row should point at real bytes", file.isFile)
        // A deliberately distinctive original modification time that the rename must not change.
        val originalModified = 1_004_000_000_000L
        check(file.setLastModified(originalModified))
        val baseline = snapshot(before)

        val plan = BatchRenameEngine.preview(listOf(file), BatchRenameEngine.Options(rules = listOf(rule("_改名"))))
        assertTrue(plan.errors.toString(), plan.canExecute)
        val result = MediaStoreRename.execute(context, plan)
        if (!result.success) {
            val current = runCatching { snapshot(before) }.getOrNull()
            Log.e("Ui109Time", "rename failed: ${result.message}\nbaseline=$baseline\ncurrent=$current\n" +
                "rowAtSource=${rowsAt(file)}\nrowAtTarget=${rowsAt(File(file.parentFile, file.nameWithoutExtension + "_改名." + file.extension))}")
        }
        assertTrue("rename failed: ${result.message}", result.success)
        assertEquals(1, result.renamedCount)

        val finalFile = File(file.parentFile, file.nameWithoutExtension + "_改名." + file.extension)
        files += finalFile
        assertTrue("renamed file missing", finalFile.isFile)
        assertEquals("filesystem modification time changed", originalModified, finalFile.lastModified())

        // DATE_ADDED/DATE_TAKEN identify the original item and must never move.
        val after = snapshot(before)
        assertEquals("_ID", baseline.id, after.id)
        assertEquals("DATE_ADDED", baseline.added, after.added)
        assertEquals("DATE_TAKEN", baseline.taken, after.taken)
        // DATE_MODIFIED is the provider's view of the file modification time; the file kept
        // 2001-10-09, so the row must describe that same original time, not the rename instant.
        val fileSeconds = finalFile.lastModified() / 1000L
        assertNotNull("DATE_MODIFIED missing", after.modified)
        assertTrue("DATE_MODIFIED ${after.modified} should describe the original file time $fileSeconds",
            kotlin.math.abs(after.modified!! - fileSeconds) <= 1L)
        assertEquals("DATA", finalFile.canonicalPath, File(after.path).canonicalPath)
        assertEquals("DISPLAY_NAME", finalFile.name, after.name)
        assertEquals("exactly one row for the new path", 1, rowsAt(finalFile).size)
        // Same underlying MediaStore row (same _ID); collection URIs differ by media type path.
        assertEquals("row still bound to the original item id", baseline.id,
            rowsAt(finalFile).single().lastPathSegment?.toLong())
    }

    @Test fun twoIndexedImagesKeepIndependentRows() {
        val first = createIndexedImage("qa109-pair-a-${System.nanoTime()}.jpg")
        val second = createIndexedImage("qa109-pair-b-${System.nanoTime()}.jpg")
        val a = snapshot(first); val b = snapshot(second)
        val fileA = File(a.path).also { files += it }
        val fileB = File(b.path).also { files += it }
        check(fileA.setLastModified(1_012_000_000_000L))
        check(fileB.setLastModified(1_022_000_000_000L))

        val plan = BatchRenameEngine.preview(listOf(fileA, fileB),
            BatchRenameEngine.Options(rules = listOf(RenamerRule(Kind.SERIALIZE, mode = "prefix", text = "qa109-", start = 1, step = 1, width = 2))))
        assertTrue(plan.errors.toString(), plan.canExecute)
        val result = MediaStoreRename.execute(context, plan)
        assertTrue("batch rename failed: ${result.message}", result.success)
        assertEquals(2, result.renamedCount)

        val afterA = snapshot(first); val afterB = snapshot(second)
        assertEquals(a.id, afterA.id); assertEquals(b.id, afterB.id)
        assertEquals(a.added, afterA.added); assertEquals(b.added, afterB.added)
        assertEquals(a.taken, afterA.taken); assertEquals(b.taken, afterB.taken)
        val fileA2 = File(afterA.path).also { files += it }
        val fileB2 = File(afterB.path).also { files += it }
        assertEquals(1_012_000_000_000L, fileA2.lastModified())
        assertEquals(1_022_000_000_000L, fileB2.lastModified())
        assertTrue(kotlin.math.abs(afterA.modified!! - 1_012_000_000L) <= 1L)
        assertTrue(kotlin.math.abs(afterB.modified!! - 1_022_000_000L) <= 1L)
    }

    @Test fun unindexedAppPrivateFileRenamesWithoutGainingARow() {
        val dir = File(context.getExternalFilesDir(null), "qa109-private-${System.nanoTime()}")
        check(dir.mkdirs())
        val file = File(dir, "notes 100%.txt").apply { writeText("payload") }
        check(file.setLastModified(1_030_000_000_000L))
        val plan = BatchRenameEngine.preview(listOf(file),
            BatchRenameEngine.Options(rules = listOf(RenamerRule(Kind.INSERT, text = "_v2", mode = "suffix"))))
        assertTrue(plan.errors.toString(), plan.canExecute)
        val result = MediaStoreRename.execute(context, plan)
        assertTrue("private rename failed: ${result.message}", result.success)
        val target = File(dir, "notes 100%_v2.txt")
        assertTrue(target.isFile)
        assertEquals(1_030_000_000_000L, target.lastModified())
        assertTrue("unindexed file must not become a media row", rowsAt(target).isEmpty())
        dir.deleteRecursively()
    }

    @Test fun singleRenameKeepsTheRowAndItsDatesToo() {
        val uri = createIndexedImage("qa109-single-${System.nanoTime()}.jpg")
        val baseline = snapshot(uri)
        val source = File(baseline.path).also { files += it }
        check(source.setLastModified(1_040_000_000_000L))
        val result = MediaStoreRename.renameSingle(context, source, "qa109-single-renamed.jpg")
        assertTrue("single rename failed: ${result.message}", result.success)
        val target = File(source.parentFile, "qa109-single-renamed.jpg").also { files += it }
        assertTrue("renamed file missing", target.isFile)
        assertTrue("source name still exists", !source.exists())
        assertEquals(1_040_000_000_000L, target.lastModified())
        val after = snapshot(uri)
        assertEquals(baseline.id, after.id)
        assertEquals(baseline.added, after.added)
        assertEquals(baseline.taken, after.taken)
        assertTrue(kotlin.math.abs(after.modified!! - 1_040_000_000L) <= 1L)
        assertEquals("row now points at the new path", target.canonicalPath, File(after.path).canonicalPath)
        assertEquals(1, rowsAt(target).size)
    }

    @Test fun singleRenameRejectsAPathSeparatorWithoutTouchingTheFile() {
        val uri = createIndexedImage("qa109-reject-${System.nanoTime()}.jpg")
        val baseline = snapshot(uri)
        val source = File(baseline.path).also { files += it }
        val result = MediaStoreRename.renameSingle(context, source, "../escape.jpg")
        assertTrue("unsafe name must be refused", !result.success)
        assertTrue(source.isFile)
        val after = snapshot(uri)
        assertEquals(baseline.path, after.path)
        assertEquals(baseline.id, after.id)
    }

    private fun rowsAt(file: File): List<Uri> {
        val collection = MediaStore.Files.getContentUri("external")
        return resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA} = ?", arrayOf(file.path), null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(Uri.withAppendedPath(collection, cursor.getLong(0).toString())) }
        }
    }

    @Test fun mediaStoreRowIsReallyTheSameItemAfterRename() {
        val uri = createIndexedImage("qa109-identity-${System.nanoTime()}.jpg")
        val baseline = snapshot(uri)
        assertNotNull(baseline.added)
        val file = File(baseline.path).also { files += it }
        val plan = BatchRenameEngine.preview(listOf(file), BatchRenameEngine.Options(rules = listOf(rule("_same"))))
        assertTrue(MediaStoreRename.execute(context, plan).success)
        val after = snapshot(uri)
        assertEquals(baseline.id, after.id)
        assertEquals(baseline.added, after.added)
        assertTrue("DATE_ADDED should stay a plausible original insertion time", (after.added ?: 0L) > 0L)
        File(after.path).also { files += it }
    }
}
