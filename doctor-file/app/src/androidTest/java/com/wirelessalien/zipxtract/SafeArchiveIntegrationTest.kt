package com.wirelessalien.zipxtract

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.helper.*
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SafeArchiveIntegrationTest {
    private lateinit var root: File
    @Before fun setup() {
        root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"archive-test-${UUID.randomUUID()}")
        root.mkdirs()
    }
    @After fun clean() { root.deleteRecursively() }
    private fun inputs(): File = File(root,"资料").apply {
        mkdirs(); File(this,"空目录").mkdir(); File(this,"子目录").mkdir()
        File(this,"中文.txt").writeText("你好，7-Zip！\n".repeat(40))
        File(this,"子目录/payload.bin").writeBytes(java.util.Random(23).let { r -> ByteArray(170000).also { r.nextBytes(it) } })
        File(this,"zero").writeBytes(byteArrayOf())
    }
    private fun options(format: String, delete: Boolean=false, password: String?=null, split: Long=0): SafeArchiveEngine.Options {
        val params=ZipParameters().apply {
            compressionMethod=CompressionMethod.DEFLATE
            if (password!=null) { isEncryptFiles=true; encryptionMethod=EncryptionMethod.AES; aesKeyStrength=AesKeyStrength.KEY_STRENGTH_256 }
        }
        return SafeArchiveEngine.Options(format,"测试",File(root,"output"),password,solid=true,zipParameters=params,splitSize=split,deleteSources=delete)
    }
    private fun roundTrip(format: String, delete: Boolean=false, password: String?=null, split: Long=0) {
        val source=inputs()
        val result=SafeArchiveEngine().create(listOf(source.path), options(format,delete,password,split))
        assertTrue(result.archive.length()>0); assertEquals(!delete,source.exists()); assertTrue(result.retainedSources.isEmpty())
        assertFalse(File(root,"output").listFiles()!!.any { it.name.startsWith(".sevenzip-") })
    }
    @Test fun sevenZipKeepsOriginalsByDefault() = roundTrip("7Z")
    @Test fun sevenZipEncryptedVerifiedDelete() = roundTrip("7Z",true,"密码-123")
    @Test fun zipKeepsOriginalsByDefault() = roundTrip("ZIP")
    @Test fun zipAesVerifiedDelete() = roundTrip("ZIP",true,"密码-123")
    @Test fun splitZipAesVerifiedDelete() = roundTrip("ZIP",true,"password",65536)
    @Test fun tarVerifiedDelete() = roundTrip("TAR_ONLY",true)
    @Test fun gzipTarVerifiedDelete() = roundTrip("gz",true)
    @Test fun xzTarVerifiedDelete() = roundTrip("xz",true)
    @Test fun bzip2TarVerifiedDelete() = roundTrip("bzip2",true)
    @Test fun zstdTarVerifiedDelete() = roundTrip("zstd",true)
    @Test fun lzmaTarVerifiedDelete() = roundTrip("lzma",true)
    @Test fun outputInsideSourceKeepsOriginals() {
        val source=inputs()
        assertThrows(IOException::class.java) { SafeArchiveEngine().create(listOf(source.path),options("7Z",true).copy(outputDir=source)) }
        assertTrue(File(source,"中文.txt").exists())
    }
    @Test fun changedSourceBeforeVerificationKeepsAllOriginals() {
        val source=inputs()
        val engine=SafeArchiveEngine(phase={ if(it.contains("逐项")) File(source,"中文.txt").appendText("changed") })
        assertThrows(Exception::class.java) { engine.create(listOf(source.path),options("ZIP",true)) }
        assertTrue(File(source,"中文.txt").exists()); assertTrue(File(source,"子目录/payload.bin").exists())
    }
    @Test fun corruptArchiveNeverDeletesOriginals() {
        val source=inputs()
        val engine=SafeArchiveEngine(phase={ if(it.contains("逐项")) File(root,"output").walkTopDown().first { f->f.extension=="zip" }.writeText("corrupt") })
        assertThrows(Exception::class.java) { engine.create(listOf(source.path),options("ZIP",true)) }
        assertTrue(source.exists()); assertTrue(File(source,"中文.txt").exists())
    }
    @Test fun cancellationKeepsOriginalsAndRemovesStaging() {
        val source=inputs(); var cancelled=false
        val engine=SafeArchiveEngine({cancelled},phase={if(it=="正在压缩…") cancelled=true})
        assertThrows(Exception::class.java) { engine.create(listOf(source.path),options("7Z",true)) }
        assertTrue(File(source,"中文.txt").exists()); assertTrue(File(root,"output").listFiles()!!.isEmpty())
    }
    @Test fun outputCollisionKeepsPreviousArchive() {
        val source=inputs(); val o=options("ZIP")
        val first=SafeArchiveEngine().create(listOf(source.path),o).archive
        val bytes=first.readBytes(); val second=SafeArchiveEngine().create(listOf(source.path),o).archive
        assertNotEquals(first.name,second.name); assertArrayEquals(bytes,first.readBytes())
    }
    @Test fun symlinkIsRejected() {
        val source=inputs(); val outside=File(root,"outside").apply{writeText("keep")}
        android.system.Os.symlink(outside.path,File(source,"link").path)
        assertThrows(IOException::class.java) { SafeArchiveEngine().create(listOf(source.path),options("ZIP",true)) }
        assertEquals("keep",outside.readText())
    }
    @Test fun archiveNameTraversalIsRejected() {
        val source=inputs()
        assertThrows(IllegalArgumentException::class.java) { SafeArchiveEngine().create(listOf(source.path),options("ZIP",true).copy(name="../bad")) }
        assertTrue(source.exists())
    }
}
