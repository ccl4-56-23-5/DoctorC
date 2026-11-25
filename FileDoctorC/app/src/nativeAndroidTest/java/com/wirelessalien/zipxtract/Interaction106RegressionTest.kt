package com.wirelessalien.zipxtract

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.nativeui.GestureDock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class Interaction106RegressionTest{
    private val ins=InstrumentationRegistry.getInstrumentation();private val context=ins.targetContext
    private lateinit var folder:File;private var scenario:ActivityScenario<MainActivity>?=null
    @Before fun setup(){
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")).use{it.readBytes()}
        folder=File(context.getExternalFilesDir(null),"interaction106-${System.nanoTime()}").apply{mkdirs()}
    }
    @After fun cleanup(){scenario?.close();folder.deleteRecursively()}
    private fun launch(){
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DIRECTORY).putExtra(MainActivity.EXTRA_DIRECTORY_PATH,folder.path))
        settle()
    }
    private fun settle(){SystemClock.sleep(550);ins.waitForIdleSync()}
    private fun field(a:MainActivity,key:String):Any?=MainActivity::class.java.getDeclaredField(key).apply{isAccessible=true}.get(a)
    private fun row(a:MainActivity)=((a.recycler.findViewHolderForAdapterPosition(1)!!.itemView as ViewGroup).getChildAt(0) as ViewGroup)
    @Test fun independentDockAndSearchUseReferenceRatiosAndSquareArtwork(){
        File(folder,"one.txt").writeText("fixture");launch()
        scenario!!.onActivity{a->
            val host=a.findViewById<View>(R.id.native_dock_host)
            val dock=a.findViewById<GestureDock>(R.id.native_dock)
            val search=a.findViewById<View>(R.id.native_search_dock)
            assertTrue(kotlin.math.abs((host.width*.45f).roundToInt()-dock.width)<=2)
            assertEquals((host.width*.145f).roundToInt(),dock.height)
            assertEquals(search.width,search.height);assertEquals(dock.height,search.height)
            assertEquals((host.width*.03f).roundToInt(),search.left-dock.right)
            val tabs=dock.getChildAt(1) as ViewGroup
            for(i in 0 until tabs.childCount){
                val tab=tabs.getChildAt(i) as ViewGroup;val icon=tab.getChildAt(0) as ImageView
                assertEquals(icon.width,icon.height);assertEquals(ImageView.ScaleType.FIT_CENTER,icon.scaleType)
                assertEquals(1f,tab.scaleX);assertEquals(1f,tab.scaleY)
            }
        }
    }
    @Test fun leadingFolderIconEntersAndLongPressRetainsSelection(){
        val child=File(folder,"child").apply{mkdirs()};File(child,"file.txt").writeText("fixture");launch()
        scenario!!.onActivity{row(it).getChildAt(0).performClick()};settle()
        scenario!!.onActivity{a->assertEquals(child,field(a,"directory"));assertTrue((field(a,"selected") as Set<*>).isEmpty());a.onBackPressed()};settle()
        scenario!!.onActivity{a->
            assertEquals(folder,field(a,"directory"));row(a).getChildAt(0).performLongClick()
            assertEquals(setOf(child.path),field(a,"selected"))
        }
    }
    @Test fun leadingArchiveIconViewsArchiveInsteadOfSelecting(){
        val zip=File(folder,"archive.zip")
        ZipOutputStream(zip.outputStream()).use{it.putNextEntry(ZipEntry("fixture.txt"));it.write("fixture".toByteArray());it.closeEntry()}
        launch();scenario!!.onActivity{row(it).getChildAt(0).performClick()};settle()
        scenario!!.onActivity{a->assertTrue((field(a,"selected") as Set<*>).isEmpty());assertEquals(MainActivity.Page.ARCHIVE,field(a,"page"))}
    }
}
