package com.wirelessalien.zipxtract

import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.nativeui.CategoryCatalog
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryCatalogDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private lateinit var catalog:CategoryCatalog
    private lateinit var root:File
    private lateinit var prefix:String
    @Before fun setup(){
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")).use{it.readBytes()}
        prefix="zipdoctorcCatalog${System.nanoTime()}"
        root=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"ZipDoctorC-CatalogQA-$prefix").apply{check(mkdirs())}
        catalog=CategoryCatalog(context)
    }
    @After fun cleanup(){
        if(::catalog.isInitialized)catalog.close()
        if(::root.isInitialized){
            val downloads=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
            check(root.canonicalFile.parentFile==downloads&&root.name.startsWith("ZipDoctorC-CatalogQA-"))
            root.deleteRecursively()
        }
    }
    private suspend fun complete()=withTimeout(20_000){catalog.snapshots("文档",prefix,roots=listOf(root)).first{it.complete}}
    private fun own(snapshot:CategoryCatalog.Snapshot)=snapshot.entries.filter{it.file.parentFile==root&&it.file.name.startsWith(prefix)}
    @Test fun unindexedDocumentsAreSupplementedPastOldThreeHundredLimit()=runBlocking {
        // No MediaScanner call: an index-only implementation must not silently omit these files.
        repeat(360){File(root,"$prefix-$it.txt").writeText("fixture $it")}
        val rows=own(complete())
        assertEquals(360,rows.size);assertEquals(360,rows.map{it.file.path}.toSet().size)
        assertTrue(rows.all{it.bytes>0&&it.modified>0})
    }
    @Test fun cachedRevisitAndInvalidationReflectNewAndDeletedDocuments()=runBlocking {
        val retained=File(root,"$prefix-retained.pdf").apply{writeText("fixture")}
        val removed=File(root,"$prefix-removed.txt").apply{writeText("fixture")}
        assertEquals(setOf(retained.path,removed.path),own(complete()).map{it.file.path}.toSet())
        val cached=withTimeout(2_000){catalog.snapshots("文档",prefix,roots=listOf(root)).first()}
        assertTrue(cached.cached);assertTrue(cached.complete)
        assertEquals(setOf(retained.path,removed.path),own(cached).map{it.file.path}.toSet())
        assertTrue(removed.delete());val created=File(root,"$prefix-created.docx").apply{writeText("fixture")}
        catalog.invalidate()
        val refreshed=complete();assertFalse(refreshed.cached)
        assertEquals(setOf(retained.path,created.path),own(refreshed).map{it.file.path}.toSet())
    }
}
