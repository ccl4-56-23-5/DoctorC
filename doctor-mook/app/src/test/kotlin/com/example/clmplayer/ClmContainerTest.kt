package com.example.clmplayer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class ClmContainerTest {
    private val fixturePassphrase = "clm-cross-runtime-test-only-2026"
    private class MemorySource(val bytes: ByteArray) : ClmSource {
        override val size get() = bytes.size.toLong()
        var consumed = 0
        override fun close() {}
        override fun readAt(position: Long, target: ByteArray, offset: Int, count: Int): Int {
            if (position >= size) return -1
            val n = minOf(count, bytes.size - position.toInt())
            bytes.copyInto(target, offset, position.toInt(), position.toInt() + n); consumed += n
            return n
        }
    }
    private fun fixture() = javaClass.getResourceAsStream("/clm2-dotnet.clm")!!.use { it.readBytes() }
    private fun decode(s: String) = Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
    private fun entries(c: ClmContainer): List<ClmEntry> {
        val r = Regex("\"offset\":(\\d+),\"length\":(\\d+),\"noncePrefix\":\"([^\"]+)\",\"mime\":\"([^\"]+)\"")
        return r.findAll(c.manifest).mapIndexed { i, m -> ClmEntry(m.groupValues[1].toLong(), m.groupValues[2].toLong(), Base64.getDecoder().decode(decode(m.groupValues[3])), i, m.groupValues[4]) }.toList()
    }
    @Test fun dotNetManifestDecryptsAndOpeningDoesNotReadAudio() {
        val source = MemorySource(fixture())
        ClmContainer(source, fixturePassphrase).use { c ->
            assertTrue(decode(c.manifest).contains("跨语言协议测试"))
            assertEquals(4, entries(c).size)
            c.validate(entries(c))
            assertTrue(source.consumed < 4096)
        }
    }
    @Test fun randomReadsAcrossChunksMatchOriginalBytes() {
        ClmContainer(MemorySource(fixture()), fixturePassphrase).use { c ->
            val e = entries(c)[1]
            c.reader(e).use { reader ->
                for (position in listOf(65520L, 131100L, 13L, 0L, 65536L)) {
                    val dest = ByteArray(70321)
                    val n = reader.readAt(position, dest, 0, dest.size)
                    assertEquals(minOf(dest.size.toLong(), e.length - position).toInt(), n)
                    for (i in 0 until n) assertEquals(((position + i) * 31 % 256).toByte(), dest[i])
                }
                assertEquals(-1, reader.readAt(e.length, ByteArray(1), 0, 1))
                assertEquals(0, reader.readAt(e.length, ByteArray(0), 0, 0))
            }
        }
    }
    @Test fun exactBlockAndShortFinalTracksWork() {
        ClmContainer(MemorySource(fixture()), fixturePassphrase).use { c ->
            for (e in entries(c).drop(2)) c.reader(e).use { r ->
                val data = ByteArray(e.length.toInt())
                assertEquals(data.size, r.readAt(0, data, 0, data.size))
                for (i in data.indices) assertEquals(((i * 31 + e.ordinal - 1) % 256).toByte(), data[i])
            }
        }
    }
    @Test fun wrongPasswordAndManifestTamperingFail() {
        assertThrows(IOException::class.java) { ClmContainer(MemorySource(fixture()), "wrong") }
        val bytes = fixture(); bytes[75] = (bytes[75].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { ClmContainer(MemorySource(bytes), fixturePassphrase) }
    }
    @Test fun tamperedAudioFailsAuthentication() {
        val bytes = fixture()
        val c = ClmContainer(MemorySource(bytes), fixturePassphrase)
        val e = entries(c)[1]
        val p = (c.payloadStart + e.offset + 10).toInt()
        bytes[p] = (bytes[p].toInt() xor 1).toByte()
        c.reader(e).use { r -> assertThrows(IOException::class.java) { r.readAt(0, ByteArray(100), 0, 100) } }
        c.close()
    }
    @Test fun modifiedEntryBoundsAndTruncatedFilesAreRejected() {
        ClmContainer(MemorySource(fixture()), fixturePassphrase).use { c ->
            val es = entries(c).toMutableList(); es[1] = es[1].copy(offset = Long.MAX_VALUE)
            assertThrows(IllegalArgumentException::class.java) { c.validate(es) }
        }
        val bytes = fixture().copyOf(100)
        assertThrows(IOException::class.java) { ClmContainer(MemorySource(bytes), fixturePassphrase) }
    }
    @Test fun legacyZipCannotBeOpenedAsEncryptedClm() {
        val bytes = ByteArray(64); bytes[0] = 80; bytes[1] = 75
        assertThrows(IOException::class.java) { ClmContainer(MemorySource(bytes), fixturePassphrase) }
    }
}
