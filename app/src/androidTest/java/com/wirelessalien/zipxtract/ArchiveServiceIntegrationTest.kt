package com.wirelessalien.zipxtract

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.fragment.MainFragment
import com.wirelessalien.zipxtract.helper.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.lingala.zip4j.model.enums.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArchiveServiceIntegrationTest {
    @Test fun threeDialogRoutesReachVerifiedDeleteService() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        val root=File(context.cacheDir,"service-qa-${System.nanoTime()}").apply{mkdirs()}
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                instrumentation.waitForIdleSync()
                for (format in listOf("7Z","ZIP","TAR")) {
                    val input=File(root,"$format.txt").apply{writeText("service roundtrip")}
                    val result=async(start=CoroutineStart.UNDISPATCHED) {
                        withTimeout(60000) { EventBus.events.first { it is AppEvent.ArchiveComplete || it is AppEvent.ArchiveError } }
                    }
                    scenario.onActivity { activity ->
                        val fragment=activity.supportFragmentManager.findFragmentById(R.id.container) as MainFragment
                        when(format) {
                            "7Z" -> fragment.startSevenZService(null,"service-7z",3,false,2,listOf(input.path),File(root,"out").path,true)
                            "ZIP" -> fragment.startZipService("service-zip",null,CompressionMethod.DEFLATE,CompressionLevel.NORMAL,false,null,null,listOf(input.path),File(root,"out").path,true)
                            else -> fragment.startArchiveTarService(listOf(input.path),"service-tar","TAR_ONLY",File(root,"out").path,3,true)
                        }
                    }
                    val event=result.await()
                    assertTrue("$format result: $event",event is AppEvent.ArchiveComplete)
                    assertFalse("$format original deleted",input.exists())
                    // Wait for stopSelf before allowing the next task.
                    android.os.SystemClock.sleep(300)
                }
            }
        } finally { root.deleteRecursively() }
    }
}
