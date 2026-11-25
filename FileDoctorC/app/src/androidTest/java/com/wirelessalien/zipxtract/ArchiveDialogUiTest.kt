package com.wirelessalien.zipxtract

import android.content.Intent
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.fragment.*
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArchiveDialogUiTest {
    @Test fun allCreationDialogsStartWithDeleteOffAndUseWindowMotion() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
        instrumentation.runOnMainSync {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("zh-CN"))
        }
        val input=File(context.cacheDir,"ui-dialog-input.txt").apply{writeText("UI smoke")}
        val dao=FileOperationsDao(context)
        val intent=Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            for (type in listOf("7Z","ZIP","TAR")) {
                val job=dao.addFilesForJob(listOf(input.path))
                val dialog=when(type) {
                    "7Z" -> SevenZOptionDialogFragment.newInstance(job)
                    "ZIP" -> ZipOptionDialogFragment.newInstance(job)
                    else -> TarOptionsDialogFragment.newInstance(job)
                }
                scenario.onActivity { dialog.showNow(it.supportFragmentManager,"test-$type") }
                val deadline=android.os.SystemClock.elapsedRealtime()+10000
                var ready=false
                while(!ready && android.os.SystemClock.elapsedRealtime()<deadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { ready=dialog.dialog?.findViewById<android.view.View>(R.id.deleteSourcesCheckBox)?.isShown==true }
                    if(!ready) android.os.SystemClock.sleep(100)
                }
                scenario.onActivity {
                    assertTrue("$type checkbox ready",ready)
                    val checkbox=dialog.dialog!!.findViewById<CheckBox>(R.id.deleteSourcesCheckBox)
                    assertFalse(checkbox.isChecked)
                    assertEquals(R.style.ArchiveDialogAnimation,dialog.dialog!!.window!!.attributes.windowAnimations)
                    checkbox.requestRectangleOnScreen(android.graphics.Rect(0,0,checkbox.width,checkbox.height),true)
                    var ancestor: android.view.ViewParent?=checkbox.parent
                    while(ancestor!=null) {
                        if(ancestor is android.widget.ScrollView) ancestor.fullScroll(android.view.View.FOCUS_DOWN)
                        ancestor=ancestor.parent
                    }
                }
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(300)
                val picture=instrumentation.uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null),"qa-$type.png").outputStream().use {
                    picture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
                picture.recycle()
                scenario.onActivity {
                    val checkbox=dialog.dialog!!.findViewById<CheckBox>(R.id.deleteSourcesCheckBox)
                    checkbox.performClick(); assertTrue(checkbox.isChecked)
                    dialog.dismissNow()
                }
                dao.deleteFilesForJob(job)
            }
        }
        input.delete()
    }
}
