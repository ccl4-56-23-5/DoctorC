package com.wirelessalien.zipxtract

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.ServiceConstants as S
import com.wirelessalien.zipxtract.helper.*
import com.wirelessalien.zipxtract.service.SafeArchiveService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class Compression112Test {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    private lateinit var output:File
    @Before fun setup() { root=File(context.cacheDir,"compression112-${System.nanoTime()}").apply{mkdir()};output=File(root,"output") }
    @After fun cleanup(){root.deleteRecursively()}
    private fun source(name:String="source.bin",size:Int=230000)=File(root,name).apply{writeBytes(ByteArray(size).also{Random(112).nextBytes(it)})}
    private fun options(format:String="ZIP",delete:Boolean=false,split:Long=0)=SafeArchiveEngine.Options(format,"bundle",output,splitSize=split,deleteSources=delete)
    private fun noOutput(){assertTrue(output.listFiles().orEmpty().isEmpty())}

    @Test fun splitPublicationCollisionRollsBackOnlyOurPartsAndKeepsTheForeignIndex() {
        val src=source();val foreign=File(output,"bundle.zip")
        val engine=SafeArchiveEngine(phase={if(it.contains("逐项"))foreign.writeText("foreign archive")})
        assertThrows(Exception::class.java){engine.create(listOf(src.path),options(delete=true,split=65536))}
        assertEquals("foreign archive",foreign.readText());assertTrue(src.isFile)
        assertEquals(listOf("bundle.zip"),output.listFiles()!!.map{it.name})
    }
    @Test fun cancellationOnceAVolumeHasBeenPublishedRemovesAllOwnedVolumes() {
        val src=source()
        assertThrows(CancellationException::class.java){
            SafeArchiveEngine({File(output,"bundle.z01").exists()}).create(listOf(src.path),options(delete=true,split=65536))
        }
        assertTrue(src.isFile);noOutput()
    }
    @Test fun deletedOrModifiedPublishedArchivePreventsSourceDeletion() {
        val src=source()
        val engine=SafeArchiveEngine(phase={if(it.startsWith("校验通过"))File(output,"bundle.zip").writeText("changed after publish")})
        assertThrows(Exception::class.java){engine.create(listOf(src.path),options(delete=true))}
        assertTrue(src.isFile);assertEquals(230000,src.length().toInt())
    }
    @Test fun cancelAtDeleteBoundaryReturnsVerifiedArchiveWithSourcesRetained() {
        val src=source();var stop=false
        val result=SafeArchiveEngine({stop},phase={if(it.startsWith("校验通过"))stop=true}).create(listOf(src.path),options(delete=true))
        assertTrue(src.isFile);assertTrue(result.retainedSources.isNotEmpty())
        val restored=SafeExtractionEngine().extract(result.archive,File(root,"restored").apply{mkdir()})
        assertArrayEquals(src.readBytes(),File(restored,src.name).readBytes())
    }
    @Test fun cancellationDuringSourceDeletionReportsOnlyRemainingFilesAndKeepsTheArchive() {
        val first=source("long-source-name.bin",10000);val second=source("b.bin",10000)
        var deleting=false
        val result=SafeArchiveEngine({deleting&&!first.exists()},phase={if(it.startsWith("校验通过"))deleting=true})
            .create(listOf(first.path,second.path),options(delete=true))
        assertFalse(first.exists());assertTrue(second.exists());assertEquals(listOf("b.bin"),result.retainedSources)
        val restored=SafeExtractionEngine().extract(result.archive,File(root,"restored").apply{mkdir()})
        assertTrue(File(restored,first.name).isFile);assertArrayEquals(second.readBytes(),File(restored,second.name).readBytes())
    }
    @Test fun unsupportedEncryptionAndSplitOptionsAreRejectedBeforeCreatingOutput() {
        val src=source(size=10)
        for(o in listOf(options("TAR_ONLY").copy(password="secret"),options("7Z",split=65536),options(split=-1),options(split=1024),options().copy(name=".zip"))){
            assertThrows(IllegalArgumentException::class.java){SafeArchiveEngine().create(listOf(src.path),o)}
            assertTrue(src.isFile);noOutput()
        }
    }
    @Test fun damagedGzipTrailerNeverPassesDeleteAfterVerification() {
        val src=source()
        val engine=SafeArchiveEngine(phase={if(it.contains("逐项")){
            val file=output.walkTopDown().first{f->f.name.endsWith(".tar.gz")}
            java.io.RandomAccessFile(file,"rw").use{it.setLength(it.length()-8)}
        }})
        assertThrows(Exception::class.java){engine.create(listOf(src.path),options("gz",delete=true))}
        assertTrue(src.exists());noOutput()
    }
    @Test fun unknownStageAdditionsAreNotPublishedOrRecursivelyDeleted() {
        val src=source();lateinit var foreign:File
        val engine=SafeArchiveEngine(phase={if(it.contains("逐项")){
            val stage=output.listFiles()!!.single{f->f.isDirectory}
            foreign=File(stage,"foreign.txt").apply{writeText("unrelated")}
        }})
        assertThrows(IOException::class.java){engine.create(listOf(src.path),options(delete=true))}
        assertTrue(src.isFile);assertEquals("unrelated",foreign.readText());assertFalse(File(output,"bundle.zip").exists())
    }
    @Test fun zipStreamPreservesAnExactUnicodeInventoryReadableByAnIndependentReader() {
        val directory=File(root,"目录 +%20 ").apply{mkdir()};File(directory,"空目录").mkdir()
        val file=File(directory," 文件 +%20.txt").apply{writeText("unicode payload")}
        val archive=SafeArchiveEngine().create(listOf(directory.path,file.path),options()).archive
        val entries=linkedMapOf<String,String>()
        ZipInputStream(archive.inputStream(),Charsets.UTF_8).use{zip->while(true){val entry=zip.nextEntry?:break;entries[entry.name]=zip.readBytes().toString(Charsets.UTF_8)}}
        assertEquals(setOf("目录 +%20 /","目录 +%20 /空目录/","目录 +%20 / 文件 +%20.txt"),entries.keys)
        assertEquals("unicode payload",entries["目录 +%20 / 文件 +%20.txt"])
    }
    @Test fun zipCallbackFailureCannotLeaveABackgroundWriterOrPartialPublishedArchive() {
        val src=source()
        assertThrows(IOException::class.java){SafeArchiveEngine(progress={throw IOException("callback failure")})
            .create(listOf(src.path),options(delete=true,split=65536))}
        Thread.sleep(200);assertTrue(src.isFile);noOutput()
    }
    @Test fun realCompressionServiceCleansItsJobBeforeCompletion() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val src=source();val dao=FileOperationsDao(context);val job=dao.addFilesForJob(listOf(src.path))
            val event=async(start=CoroutineStart.UNDISPATCHED){withTimeout(30000){EventBus.events.first{it is AppEvent.ArchiveComplete||it is AppEvent.ArchiveError}}}
            ContextCompat.startForegroundService(context,Intent(context,SafeArchiveService::class.java)
                .putExtra(S.EXTRA_JOB_ID,job).putExtra(S.EXTRA_ARCHIVE_NAME,"service").putExtra(S.EXTRA_DESTINATION_PATH,output.path)
                .putExtra(SafeArchiveService.EXTRA_FORMAT,"ZIP"))
            val result=event.await();assertTrue(result.toString(),result is AppEvent.ArchiveComplete)
            assertTrue(dao.getFilesForJob(job).isEmpty());assertTrue(src.isFile);assertTrue(File(output,"service.zip").isFile)
        }
    }
    @Test fun duplicateStartDoesNotEndTheRunningJobOrLeakTheRejectedJobRecord() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val src=source(size=4*1024*1024);val dao=FileOperationsDao(context)
            val first=dao.addFilesForJob(listOf(src.path));val second=dao.addFilesForJob(listOf(src.path));var sent=false
            fun request(job:String,name:String)=Intent(context,SafeArchiveService::class.java)
                .putExtra(S.EXTRA_JOB_ID,job).putExtra(S.EXTRA_ARCHIVE_NAME,name).putExtra(S.EXTRA_DESTINATION_PATH,output.path)
                .putExtra(SafeArchiveService.EXTRA_FORMAT,"ZIP")
            val event=async(start=CoroutineStart.UNDISPATCHED){withTimeout(30000){EventBus.events.first{
                if(it is AppEvent.ArchiveProgress&&!sent){sent=true;ContextCompat.startForegroundService(context,request(second,"rejected"))}
                it is AppEvent.ArchiveComplete||it is AppEvent.ArchiveError
            }}}
            ContextCompat.startForegroundService(context,request(first,"first"))
            val result=event.await();assertTrue(sent);assertTrue(result.toString(),result is AppEvent.ArchiveComplete)
            assertTrue(dao.getFilesForJob(first).isEmpty());assertTrue(dao.getFilesForJob(second).isEmpty())
            assertTrue(File(output,"first.zip").isFile);assertFalse(File(output,"rejected.zip").exists())
        }
    }
    @Test fun cancelBroadcastWaitsForCompressionWorkerClosureAndJobCleanup() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val src=source(size=8*1024*1024);val dao=FileOperationsDao(context);val job=dao.addFilesForJob(listOf(src.path));var sent=false
            val event=async(start=CoroutineStart.UNDISPATCHED){withTimeout(30000){EventBus.events.first{
                if(it is AppEvent.ArchiveProgress&&!sent){sent=true;context.sendBroadcast(Intent(BroadcastConstants.ACTION_CANCEL_OPERATION).setPackage(context.packageName))}
                it is AppEvent.ArchiveComplete||it is AppEvent.ArchiveError
            }}}
            ContextCompat.startForegroundService(context,Intent(context,SafeArchiveService::class.java)
                .putExtra(S.EXTRA_JOB_ID,job).putExtra(S.EXTRA_ARCHIVE_NAME,"cancel").putExtra(S.EXTRA_DESTINATION_PATH,output.path)
                .putExtra(SafeArchiveService.EXTRA_FORMAT,"ZIP"))
            val result=event.await();assertTrue(sent);assertTrue(result.toString(),result is AppEvent.ArchiveError)
            assertTrue(dao.getFilesForJob(job).isEmpty());assertTrue(src.isFile);noOutput()
        }
    }
}
