package com.wirelessalien.zipxtract

import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.constant.ServiceConstants as S
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.helper.*
import com.wirelessalien.zipxtract.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.lingala.zip4j.model.ZipParameters
import org.apache.commons.compress.archivers.tar.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class Extraction111Test {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private lateinit var root: File
    private lateinit var output: File
    private lateinit var sentinel: File
    @Before fun setup() {
        root = File(context.cacheDir, "extract111-${System.nanoTime()}").apply { mkdirs() }
        output = File(root, "output").apply { mkdir() }
        sentinel = File(context.filesDir, "keep111-${System.nanoTime()}").apply { writeText("existing private data") }
    }
    @After fun cleanup() { assertEquals("existing private data", sentinel.readText()); sentinel.delete(); root.deleteRecursively() }
    private fun zip(name: String = "sample.zip", entries: List<Pair<String, ByteArray>>, stored: Boolean = false): File {
        val file = File(root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, bytes) in entries) {
                val entry = ZipEntry(path)
                if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
                out.putNextEntry(entry); if (!path.endsWith('/')) out.write(bytes); out.closeEntry()
            }
        }
        return file
    }
    private fun noOutput() { assertEquals(emptyList<String>(), output.listFiles()!!.map { it.name }) }
    private fun failExtraction(file: File, password: String? = null, selected: List<String>? = null) {
        assertTrue("must fail: ${file.name}", runCatching { SafeExtractionEngine().extract(file, output, password, selected) }.isFailure)
        assertTrue("original archive retained", file.exists()); noOutput()
    }
    @Test fun zipRoundTripUnicodeSpacesAndTimestampWithoutOverwritingExistingDirectory() {
        val input = zip(entries = listOf("目录/空 格+%20.txt" to "原始内容".toByteArray(), "目录/empty/" to byteArrayOf()))
        File(output, "sample").mkdir(); File(output, "sample/user.txt").writeText("do not replace")
        val result = SafeExtractionEngine().extract(input, output)
        assertEquals("sample (1)", result.name)
        assertEquals("原始内容", File(result, "目录/空 格+%20.txt").readText())
        assertTrue(File(result, "目录/empty").isDirectory)
        assertEquals("do not replace", File(output, "sample/user.txt").readText())
    }
    @Test fun selectedVirtualFolderExpandsDescendantsAndExcludesOtherFiles() {
        val input = zip(entries = listOf("selected/a.txt" to "A".toByteArray(), "selected/deep/b.txt" to "B".toByteArray(), "other.txt" to "secret".toByteArray()))
        val result = SafeExtractionEngine().extract(input, output, selected = listOf("selected"))
        assertEquals("B", File(result, "selected/deep/b.txt").readText()); assertFalse(File(result, "other.txt").exists())
    }
    @Test fun missingSelectionDoesNotReportAnEmptySuccess() {
        val input = zip(entries = listOf("a.txt" to "A".toByteArray()))
        failExtraction(input, selected = listOf("missing"))
    }
    @Test fun conflictingFileAndDirectoryEntriesLeaveNoPartialOutput() {
        val input = zip(entries = listOf("same" to "file".toByteArray(), "same/child.txt" to "child".toByteArray()))
        failExtraction(input)
    }
    @Test fun zipTraversalIsRejectedWithoutOutsideWritesOrPartialPublishedFiles() {
        val keep = File(root, "outside.txt").apply { writeText("keep") }
        val input = zip(entries = listOf("safe.txt" to "safe".toByteArray(), "../outside.txt" to "bad".toByteArray()))
        failExtraction(input); assertEquals("keep", keep.readText())
    }
    @Test fun crcFailureAndTruncatedZipLeaveNoOutput() {
        val marker = "UNIQUE_CRC_CONTENT_111".toByteArray()
        val input = zip(entries = listOf("data.txt" to marker), stored = true)
        val bytes = input.readBytes(); val at = bytes.indices.first { i -> i + marker.size <= bytes.size && marker.indices.all { bytes[i + it] == marker[it] } }
        bytes[at] = (bytes[at].toInt() xor 1).toByte(); input.writeBytes(bytes)
        failExtraction(input)
        val broken = File(root, "broken.zip").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }; failExtraction(broken)
    }
    @Test fun zipUnixSymlinkIsRejected() {
        val input = zip(entries = listOf("link" to "../../outside".toByteArray()))
        val bytes = input.readBytes()
        val at = (0 until bytes.size - 46).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        bytes[at + 5] = 3 // UNIX creator
        bytes[at + 40] = 0xff.toByte(); bytes[at + 41] = 0xa1.toByte() // symlink mode
        input.writeBytes(bytes); failExtraction(input)
    }
    @Test fun tarLinksAreRejected() {
        val input = File(root, "links.tar")
        TarArchiveOutputStream(input.outputStream()).use { tar ->
            val e = TarArchiveEntry("link", TarConstants.LF_SYMLINK).apply { linkName = "../../outside" }
            tar.putArchiveEntry(e); tar.closeArchiveEntry(); tar.finish()
        }
        failExtraction(input)
    }
    @Test fun zipAndEncryptedSevenZipWrongPasswordNeverPublishAndCorrectPasswordsWork() {
        val src = File(root, "source.txt").apply { writeText("password-content") }
        val archives = File(root, "archives").apply { mkdir() }
        for (format in listOf("ZIP", "7Z")) {
            val file = SafeArchiveEngine().create(listOf(src.path), SafeArchiveEngine.Options(format, "encrypted-$format", archives, "pass word")).archive
            failExtraction(file, "wrong")
            val result = SafeExtractionEngine().extract(file, output, "pass word")
            assertEquals(src.readText(), File(result, src.name).readText())
            result.deleteRecursively()
        }
    }
    @Test fun everyCreatedTarCodecRoundTripsAndUnwrapsTheTarLayer() {
        val src = File(root, "子目录").apply { mkdir() }; File(src, "空 格.txt").writeText("tar data 111")
        val archives = File(root, "archives").apply { mkdir() }
        for (format in listOf("TAR_ONLY", "gz", "bzip2", "xz", "lzma", "zstd")) {
            try {
                val file = SafeArchiveEngine().create(listOf(src.path), SafeArchiveEngine.Options(format, "codec-$format", archives)).archive
                val result = SafeExtractionEngine().extract(file, output)
                assertEquals(format, "tar data 111", File(result, "子目录/空 格.txt").readText())
                assertTrue("preview must list the same inner TAR entries", SafeExtractionEngine().list(file).any { it.name == "子目录/空 格.txt" })
            } catch (e: Exception) { throw AssertionError("codec=$format", e) }
        }
    }
    @Test fun plainGzipProducesTheOriginalLeafRatherThanADataPlaceholder() {
        val input = File(root, "message.txt.gz")
        GZIPOutputStream(input.outputStream()).use { it.write("plain gzip".toByteArray()) }
        val result = SafeExtractionEngine().extract(input, output)
        assertEquals("plain gzip", File(result, "message.txt").readText())
    }
    @Test fun upstreamRar5StoredAndCompressedFixturesHaveTheirExpectedContents() {
        for (name in listOf("test_read_format_rar5_stored.rar", "test_read_format_rar5_compressed.rar")) {
            val file = File(root, name)
            ins.context.assets.open("extraction111/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            val result = SafeExtractionEngine().extract(file, output)
            if (name.contains("stored")) assertEquals("hello libarchive test suite!\n", File(result, "helloworld.txt").readText())
            else {
                val bytes = File(result, "test.bin").readBytes()
                assertEquals(1200, bytes.size); assertEquals(0x7CCA70CDL, CRC32().apply { update(bytes) }.value)
            }
        }
    }
    @Test fun upstreamRar5MultiVolumeWorksFromPartFourAndMissingPartsFailCleanly() {
        val parts = (1..8).map { i ->
            val name = "test_read_format_rar5_multiarchive.part${i.toString().padStart(2, '0')}.rar"
            File(root, name).apply { ins.context.assets.open("extraction111/$name").use { input -> outputStream().use { input.copyTo(it) } } }
        }
        val result = SafeExtractionEngine().extract(parts[3], output)
        val first = File(result, "home/antek/temp/build/unrar5/libarchive/bin/bsdcat_test")
        val second = File(result, "home/antek/temp/build/unrar5/libarchive/bin/bsdtar_test")
        assertTrue(first.isFile); assertTrue(second.isFile)
        assertEquals(0x35277473L, CRC32().apply { update(first.readBytes()) }.value)
        result.deleteRecursively(); parts[3].delete(); failExtraction(parts.first())
    }
    @Test fun sevenZipAndTarFolderSelectionExcludeUnselectedSolidAndStreamEntries() {
        val folder = File(root, "chosen").apply { mkdir() }; File(folder, "a.txt").writeText("chosen data")
        val other = File(root, "other.txt").apply { writeText("not selected") }
        val archives = File(root, "archives").apply { mkdir() }
        for (format in listOf("7Z", "TAR_ONLY")) {
            val input = SafeArchiveEngine().create(listOf(folder.path, other.path), SafeArchiveEngine.Options(format, "selected-$format", archives)).archive
            val result = SafeExtractionEngine().extract(input, output, selected = listOf("chosen"))
            assertEquals("chosen data", File(result, "chosen/a.txt").readText()); assertFalse(File(result, "other.txt").exists())
        }
    }
    @Test fun byteSplitSevenZipAndZipWorkFromASelectedLaterVolumeAndRejectMissingMiddle() {
        val src = File(root, "source.bin").apply { writeBytes(ByteArray(180000).also { Random(111).nextBytes(it) }) }
        val archives = File(root, "archives").apply { mkdir() }
        for (format in listOf("7Z", "ZIP")) {
            val archive = SafeArchiveEngine().create(listOf(src.path), SafeArchiveEngine.Options(format, "byte-$format", archives)).archive
            val parts = archive.readBytes().asList().chunked(50000).mapIndexed { index, bytes ->
                File(archives, archive.name + "." + (index + 1).toString().padStart(3, '0')).apply { writeBytes(bytes.toByteArray()) }
            }
            val result = SafeExtractionEngine().extract(parts[1], output)
            assertArrayEquals(src.readBytes(), File(result, src.name).readBytes()); result.deleteRecursively()
            parts[1].delete(); failExtraction(parts[0])
        }
    }
    @Test fun pkzipSplitUsesTheZipMasterWhenTheUserSelectsZ01() {
        val src = File(root, "random.bin").apply { writeBytes(ByteArray(230000).also { Random(112).nextBytes(it) }) }
        val master = File(root, "split.zip")
        net.lingala.zip4j.ZipFile(master).use { it.createSplitZipFile(listOf(src), ZipParameters(), true, 65536) }
        val part = File(root, "split.z01"); assertTrue(part.isFile)
        val result = SafeExtractionEngine().extract(part, output)
        assertArrayEquals(src.readBytes(), File(result, src.name).readBytes())
    }
    @Test fun cancellationDuringPayloadRemovesOwnedPartialFiles() {
        val src = zip(entries = listOf("large.bin" to ByteArray(2 * 1024 * 1024)), stored = true)
        var calls = 0
        val failed = runCatching { SafeExtractionEngine({ ++calls > 12 }).extract(src, output) }
        assertTrue(failed.exceptionOrNull().toString(), failed.exceptionOrNull() is CancellationException)
        noOutput(); assertTrue(src.exists())
    }
    @Test fun actualForegroundServiceClosesItsJobAndPreservesApplicationFiles() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val src = zip(entries = listOf("folder/a.txt" to "service data".toByteArray(), "other.txt" to "other".toByteArray()))
            val dao = FileOperationsDao(context); val job = dao.addFilesForJob(listOf(src.path))
            val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(30000) { EventBus.events.first { it is AppEvent.ExtractionComplete || it is AppEvent.ExtractionError } } }
            ContextCompat.startForegroundService(context, Intent(context, SafeExtractionService::class.java)
                .putExtra(S.EXTRA_JOB_ID, job).putExtra(S.EXTRA_DESTINATION_PATH, output.path)
                .putExtra(S.EXTRA_USE_APP_NAME_DIR, true).putStringArrayListExtra(S.EXTRA_ITEMS_TO_EXTRACT, arrayListOf("folder")))
            val result = event.await(); assertTrue(result.toString(), result is AppEvent.ExtractionComplete)
            assertEquals("service data", File((result as AppEvent.ExtractionComplete).dirPath, "folder/a.txt").readText())
            assertTrue(dao.getFilesForJob(job).isEmpty()); assertTrue(sentinel.isFile)
            assertTrue(runCatching { context.packageManager.getServiceInfo(ComponentName(context, ExtractArchiveService::class.java), 0) }.isFailure)
        }
    }
    @Test fun cancelBroadcastWaitsForCleanupAndEmitsATerminalEvent() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val payload = ByteArray(8 * 1024 * 1024)
            val src = zip("cancel.zip", (1..4).map { "file$it.bin" to payload }, stored = true)
            val dao = FileOperationsDao(context); val job = dao.addFilesForJob(listOf(src.path))
            var sent = false
            val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(30000) {
                EventBus.events.first {
                    if (it is AppEvent.ExtractionProgress && it.progress > 0 && !sent) {
                        sent = true; context.sendBroadcast(Intent(BroadcastConstants.ACTION_CANCEL_OPERATION).setPackage(context.packageName))
                    }
                    it is AppEvent.ExtractionComplete || it is AppEvent.ExtractionError
                }
            } }
            ContextCompat.startForegroundService(context, Intent(context, SafeExtractionService::class.java)
                .putExtra(S.EXTRA_JOB_ID, job).putExtra(S.EXTRA_DESTINATION_PATH, output.path))
            val result = event.await()
            assertTrue("cancel was sent", sent); assertTrue(result.toString(), result is AppEvent.ExtractionError)
            assertTrue((result as AppEvent.ExtractionError).errorMessage.contains("取消"))
            noOutput(); assertTrue(dao.getFilesForJob(job).isEmpty()); assertTrue(src.exists())
        }
    }
}
