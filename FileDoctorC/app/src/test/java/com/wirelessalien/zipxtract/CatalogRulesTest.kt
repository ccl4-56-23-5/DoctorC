package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.nativeui.CatalogRules
import com.wirelessalien.zipxtract.nativeui.CatalogFileWalker
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CatalogRulesTest {
    @Test fun classificationAndSqlShareCaseInsensitiveSuffixes(){
        assertTrue(CatalogRules.matches("文档","Report.PDF"));assertFalse(CatalogRules.matches("文档","Report.pdf.exe"))
        assertTrue(CatalogRules.matches("压缩包","archive.Z03"));assertFalse(CatalogRules.matches("压缩包","archive.z3x"))
        assertTrue(CatalogRules.matches("安装包","bundle.APKS"));assertFalse(CatalogRules.matches("视频","clip.mp4.zip"))
        val sql=CatalogRules.selection("文档","")
        assertEquals(CatalogRules.extensions.getValue("文档").map{"%.$it"},sql.args!!.toList())
        assertFalse(sql.clause!!.contains("LIMIT"))
    }
    @Test fun userSearchIsBoundAndWildcardsAreLiteral(){
        val sql=CatalogRules.selection("文档","a_50%\\quote'")
        assertFalse(sql.clause!!.contains("quote"));assertEquals("%a\\_50\\%\\\\quote'%",sql.args!!.last())
        assertNull(CatalogRules.selection("视频","").clause)
    }
    @Test fun discoveryDoesNotTruncateAfterOldTwoHundredOrDepthTwelve()=runBlocking {
        val root=Files.createTempDirectory("catalog-full-").toFile()
        try{
            val docs=File(root,"Documents").apply{mkdirs()};repeat(320){File(docs,"file-$it.pdf").writeText("fixture")}
            var deep=docs;repeat(15){deep=File(deep,"next").apply{mkdirs()}};File(deep,"deep.txt").writeText("fixture")
            val all=mutableListOf<File>();CatalogFileWalker(listOf(root)).forEachFile({}){all+=it}
            assertEquals(321,all.size);assertEquals(321,all.map{it.path}.toSet().size);assertTrue(all.any{it.name=="deep.txt"})
        }finally{root.deleteRecursively()}
    }
    @Test fun hiddenProtectedDirectoriesAndChildLinksAreNotTraversed()=runBlocking {
        val root=Files.createTempDirectory("catalog-boundaries-").toFile();val elsewhere=Files.createTempDirectory("catalog-outside-").toFile()
        try{
            File(root,"Documents").mkdirs();File(root,"Documents/visible.txt").writeText("fixture")
            File(root,".hidden").mkdirs();File(root,".hidden/hidden.txt").writeText("fixture")
            File(root,"Android/data/test").mkdirs();File(root,"Android/data/test/private.txt").writeText("fixture")
            File(elsewhere,"outside.txt").writeText("fixture")
            runCatching{Files.createSymbolicLink(File(root,"Documents/link").toPath(),elsewhere.toPath())}
            val all=mutableListOf<File>();CatalogFileWalker(listOf(root)).forEachFile({}){all+=it}
            assertEquals(listOf("visible.txt"),all.map{it.name})
        }finally{root.deleteRecursively();elsewhere.deleteRecursively()}
    }
    @Test fun cancellationCheckpointStopsWithoutInterruptingThread()=runBlocking {
        val root=Files.createTempDirectory("catalog-cancel-").toFile()
        try{
            repeat(500){File(root,"file-$it.txt").writeText("fixture")};var checks=0;var visited=0
            try{CatalogFileWalker(listOf(root)).forEachFile({if(++checks==25)throw CancellationException("route changed")}){visited++};fail("Cancellation must leave the walk")}
            catch(_:CancellationException){}
            assertTrue(visited<25);assertFalse(Thread.currentThread().isInterrupted)
        }finally{root.deleteRecursively()}
    }
}
