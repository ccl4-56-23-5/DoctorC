package com.wirelessalien.zipxtract
import com.wirelessalien.zipxtract.nativeui.FileManagerKernel
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
class FileManagerKernelTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun copyPreservesOriginalAndContents(){
        val src=temp.newFolder("src");File(src,"空").mkdir();File(src,"a.txt").writeText("data")
        val dst=temp.newFolder("dst");FileManagerKernel.copyOrMove(listOf(src.path),dst,false)
        assertEquals("data",File(dst,"src/a.txt").readText());assertTrue(File(dst,"src/空").isDirectory);assertTrue(src.exists())
    }
    @Test fun verifiedMoveDeletesOnlyOriginal(){
        val src=temp.newFile("a.txt").apply{writeText("data")};val dst=temp.newFolder("dst")
        assertTrue(FileManagerKernel.copyOrMove(listOf(src.path),dst,true).isEmpty());assertFalse(src.exists());assertEquals("data",File(dst,"a.txt").readText())
    }
    @Test fun copyNeverOverwritesExisting(){
        val src=temp.newFile("a.txt").apply{writeText("source")};val dst=temp.newFolder("dst");File(dst,"a.txt").writeText("keep")
        assertThrows(java.io.IOException::class.java){FileManagerKernel.copyOrMove(listOf(src.path),dst,true)}
        assertEquals("keep",File(dst,"a.txt").readText());assertTrue(src.exists())
    }
    @Test fun destinationInsideSourceIsRejected(){val src=temp.newFolder("src");val dst=File(src,"nested").apply{mkdir()};assertThrows(java.io.IOException::class.java){FileManagerKernel.copyOrMove(listOf(src.path),dst,true)};assertTrue(src.exists())}
    @Test fun renameRejectsTraversal(){val f=temp.newFile("a");assertThrows(IllegalArgumentException::class.java){FileManagerKernel.rename(f,"../b")};assertTrue(f.exists())}
}
