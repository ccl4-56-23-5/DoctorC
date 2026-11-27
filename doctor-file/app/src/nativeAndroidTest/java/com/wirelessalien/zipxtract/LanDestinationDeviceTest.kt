package com.wirelessalien.zipxtract

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.lan.FileTimes
import com.wirelessalien.zipxtract.lan.HashMismatch
import com.wirelessalien.zipxtract.lan.LanCompleted
import com.wirelessalien.zipxtract.lan.LanBatchProgress
import com.wirelessalien.zipxtract.lan.LanDestination
import com.wirelessalien.zipxtract.lan.LanFile
import com.wirelessalien.zipxtract.lan.LanLink
import com.wirelessalien.zipxtract.lan.LanReceiver
import com.wirelessalien.zipxtract.lan.LanSender
import com.wirelessalien.zipxtract.lan.LanTls
import com.wirelessalien.zipxtract.lan.lanSubnets
import com.wirelessalien.zipxtract.lan.LanTreeDestination
import com.wirelessalien.zipxtract.lan.LanTreePlan
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class LanDestinationDeviceTest {
    private lateinit var folder: File
    private val bytes = ByteArray(540_000) { (it % 251).toByte() }
    private val payload = bytes + MessageDigest.getInstance("SHA-256").digest(bytes)
    private val times = FileTimes("2017-02-03T04:05:06.123456789Z", "2019-04-05T06:07:08.456789123Z")
    private val entry = LanFile("fixture", "sample.bin", bytes.size.toLong(),
        times, "application/octet-stream")

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        folder = File(root, "lan-test-${System.nanoTime()}")
        check(folder.mkdir())
    }

    @After fun tearDown() { folder.deleteRecursively() }

    @Test fun publishesOneCopyPreservingTimesAndExistingFile() {
        val existing = File(folder, entry.name).apply { writeText("existing") }
        val receipt = LanDestination.save(folder, entry, payload.inputStream(), {}, {})
        assertEquals(0, receipt.timeWarnings)
        assertEquals("sample (1).bin", receipt.name)
        val target = File(folder, receipt.name)
        assertEquals(times, FileTimes.read(target))
        assertEquals(setOf(existing.name, target.name), folder.list()!!.toSet())
        assertArrayEquals(bytes, target.readBytes())
        assertEquals("existing", existing.readText())
    }

    @Test fun boundWiredSocketStreamsAndVerifiesPayloadOnLocalInterface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val local = lanSubnets(context).firstOrNull()
        assumeNotNull(local)
        // Use the local interface address to exercise the downstream USB socket path without cable hardware.
        val route = checkNotNull(local).copy(network = null, link = LanLink.WIRED)
        val identity = LanTls.receiver()
        val target = File(folder, "received").apply { check(mkdir()) }
        val source = File(folder, "stream.bin").apply {
            outputStream().use { stream -> repeat(16) { stream.write(bytes) } }
        }
        val receiver = LanReceiver(route, identity, { it.decision.complete(target) }, { _, _ -> }, { _, _ -> })
        receiver.use {
            it.start(5000)
            val result = LanSender(it.endpoint, identity.fingerprint, route, supportsTree = true)
                .send(listOf(source), "FileDoctorC QA") {}
            assertEquals(null, result.error)
            assertEquals(1L, result.files)
            fun digest(file: File): List<Byte> {
                val hash = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        hash.update(buffer, 0, count)
                    }
                }
                return hash.digest().toList()
            }
            assertEquals(digest(source), digest(File(target, source.name)))
        }
    }

    @Test fun shortUploadAndHashMismatchLeaveNoFiles() {
        val short = runCatching {
            LanDestination.save(folder, entry, bytes.copyOf(500).inputStream(), {}, {})
        }.exceptionOrNull()
        assertTrue(short is EOFException)
        val altered = payload.clone().apply { this[0] = 42 }
        val mismatch = runCatching {
            LanDestination.save(folder, entry, altered.inputStream(), {}, {})
        }.exceptionOrNull()
        assertTrue(mismatch is HashMismatch)
        assertTrue(folder.list()!!.isEmpty())
    }

    @Test fun interruptedUploadAndTraversalLeaveNoFiles() {
        var checks = 0
        val cancelled = runCatching {
            LanDestination.save(folder, entry, payload.inputStream(), {
                if (++checks == 2) throw IOException("cancelled")
            }, {})
        }.exceptionOrNull()
        assertEquals("cancelled", cancelled?.message)
        val invalid = runCatching {
            LanDestination.save(folder, entry.copy(name = "../outside.bin"), payload.inputStream(), {}, {})
        }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
        assertTrue(folder.list()!!.isEmpty())
        assertTrue(!File(folder.parentFile, "outside.bin").exists())
    }

    @Test fun interruptedTreeKeepsPublishedFilesAndRemovesIncompleteEmptyFolders() {
        val source = File(folder, "source").apply { check(mkdir()) }
        val target = File(folder, "target").apply { check(mkdir()) }
        val complete = File(source, "complete.txt").apply { writeText("complete") }
        val tree = File(source, "folder").apply { check(mkdir()) }
        File(tree, "incomplete.bin").writeBytes(bytes)
        val plan = LanTreePlan.scan(listOf(complete, tree), {})
        val wire = ByteArrayOutputStream().also { plan.write(it, {}, {}) }.toByteArray()
        val completed = LanCompleted()
        val truncated = wire.copyOf(wire.size - 100)

        val failure = runCatching {
            val progress = LanBatchProgress(3, plan.offer.contentBytes) {}
            LanTreeDestination.receive(target, plan.offer, truncated.inputStream(), completed, {}, progress)
        }.exceptionOrNull()

        assertTrue(failure is EOFException)
        assertEquals(1L, completed.result().files)
        assertEquals(0L, completed.result().folders)
        assertEquals(listOf(complete.name), target.list()!!.toList())
        assertEquals("complete", File(target, complete.name).readText())
    }
}
