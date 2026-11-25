package com.wirelessalien.zipxtract
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.constant.ServiceConstants as S
import com.wirelessalien.zipxtract.helper.*
import com.wirelessalien.zipxtract.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.enums.CompressionMethod
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeServiceRegressionTest {
    @Test fun legacyLevelExtrasCannotAlterFixedLosslessProfile()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"service-101-${System.nanoTime()}").apply{mkdirs()}
        try{ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{
            val file=File(root,"source.txt").apply{writeText("lossless-data".repeat(10000))}
            val job=FileOperationsDao(context).addFilesForJob(listOf(file.path))
            val result=async(start=CoroutineStart.UNDISPATCHED){withTimeout(60000){EventBus.events.first{it is AppEvent.ArchiveComplete||it is AppEvent.ArchiveError}}}
            ContextCompat.startForegroundService(context,Intent(context,SafeArchiveService::class.java).apply{
                putExtra(S.EXTRA_JOB_ID,job);putExtra(S.EXTRA_ARCHIVE_NAME,"fixed");putExtra(S.EXTRA_DESTINATION_PATH,root.path)
                putExtra(SafeArchiveService.EXTRA_FORMAT,"ZIP")
                putExtra("compressionLevel",0);putExtra("compressionMethod",CompressionMethod.STORE)
            })
            assertTrue(result.await().toString(),File(root,"fixed.zip").exists());assertTrue(file.exists())
            ZipFile(File(root,"fixed.zip")).use{z->assertEquals(CompressionMethod.DEFLATE,z.fileHeaders.single().compressionMethod);assertEquals(file.readText(),z.getInputStream(z.fileHeaders.single()).bufferedReader().use{it.readText()})}
        }}finally{root.deleteRecursively()}
    }
    @Test fun nativeForegroundFileMoveVerifiesAndKeepsConflictingFiles()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"files-101-${System.nanoTime()}").apply{mkdirs()}
        try{ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{
            val file=File(root,"source.txt").apply{writeText("file move data")};val dst=File(root,"dest").apply{mkdir()}
            val job=FileOperationsDao(context).addFilesForJob(listOf(file.path))
            val result=async(start=CoroutineStart.UNDISPATCHED){withTimeout(60000){EventBus.events.first{it is AppEvent.ArchiveComplete||it is AppEvent.ArchiveError}}}
            ContextCompat.startForegroundService(context,Intent(context,NativeFileService::class.java).putExtra("job",job).putExtra("operation","move").putExtra("destination",dst.path))
            assertTrue(result.await() is AppEvent.ArchiveComplete);assertFalse(file.exists());assertEquals("file move data",File(dst,"source.txt").readText())
        }}finally{root.deleteRecursively()}
    }
}
