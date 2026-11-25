package com.wirelessalien.zipxtract

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.nativeui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Presentation104RegressionTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private lateinit var root:File
    private var scenario:ActivityScenario<MainActivity>?=null
    @Before fun setup(){
        for(cmd in listOf("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow","pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"))
            ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd)).use{it.readBytes()}
        root=File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),"ZipDoctorC-QA104-${System.nanoTime()}").apply{mkdirs()}
        context.getSharedPreferences("native_ui",0).edit().putString("sort","name").putBoolean("descending",false).apply()
    }
    @After fun cleanup(){scenario?.close();root.deleteRecursively()}
    private fun settle(){android.os.SystemClock.sleep(650);ins.waitForIdleSync()}
    private fun launch(directory:Boolean=true){
        val intent=Intent(context,MainActivity::class.java)
        if(directory)intent.action=MainActivity.ACTION_OPEN_DIRECTORY.also{intent.putExtra(MainActivity.EXTRA_DIRECTORY_PATH,root.path)}
        scenario=ActivityScenario.launch(intent);settle()
    }
    private fun waitUntil(predicate:()->Boolean){
        val end=android.os.SystemClock.elapsedRealtime()+8000
        while(android.os.SystemClock.elapsedRealtime()<end){if(predicate()){settle();return};android.os.SystemClock.sleep(50)}
        fail("operation did not reach its terminal state")
    }
    private fun findDescription(v:View,text:String):View?=if(v.contentDescription==text)v else if(v is ViewGroup)(0 until v.childCount).firstNotNullOfOrNull{findDescription(v.getChildAt(it),text)}else null
    private fun fileInfo(a:MainActivity,file:File){MainActivity::class.java.getDeclaredMethod("showFileInfo",File::class.java).apply{isAccessible=true}.invoke(a,file)}
    private fun save(name:String){val b=ins.uiAutomation.takeScreenshot();File(context.getExternalFilesDir(null),"qa104-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}

    @Test fun repeatedDeletionUsesFrozenPathsAndRestoresCompactDock(){
        val files=(0..1).map{File(root,"delete-$it.txt").apply{writeText("owned fixture $it")}}
        val guard=File(root,"keep.txt").apply{writeText("must survive")};launch()
        for(file in files){
            scenario!!.onActivity{a->
                val selected=MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.get(a) as MutableSet<String>
                selected.clear();selected.add(file.path)
                MainActivity::class.java.getDeclaredMethod("refreshViews",Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(a,true)
                findDescription(a.findViewById(R.id.native_dock),"更多")!!.performClick()
            };settle()
            onView(withText("删除")).inRoot(isPlatformPopup()).perform(click());settle()
            // Simulate another source changing selection while the confirmation is open.
            // The confirmed paths must remain immutable rather than reading this set later.
            scenario!!.onActivity{a->(MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.get(a) as MutableSet<String>).apply{clear();add(guard.path)}}
            onView(withText("删除")).inRoot(isDialog()).perform(click())
            waitUntil{!file.exists()&&!ActiveOperation.busy()};assertTrue(guard.exists())
            scenario!!.onActivity{a->
                val dock=a.findViewById<GestureDock>(R.id.native_dock)
                val host=a.findViewById<View>(R.id.native_dock_host)
                val expected=DockGeometry.calculate(host.width,a.resources.displayMetrics.density,a.resources.configuration.fontScale)
                assertEquals("normal dock width after each deletion",expected.capsule.width,dock.width)
                val content=dock.getChildAt(1) as ViewGroup;assertEquals(2,content.childCount)
                val search=findDescription(a.findViewById(R.id.native_dock_host),"搜索")!!
                val left=Rect();val right=Rect();dock.getGlobalVisibleRect(left);search.getGlobalVisibleRect(right)
                assertTrue("search never overlaps dock",left.right<right.left)
                assertEquals(0,(a.findViewById<View>(R.id.native_dock_host).background as android.graphics.drawable.ColorDrawable).alpha)
            }
        }
        save("delete-twice")
    }
    @Test fun resumeAndRecreationKeepFileIdentityAndPixelOffset(){
        repeat(65){File(root,"doc-${it.toString().padStart(3,'0')}.txt").writeText("fixture")};launch()
        scenario!!.onActivity{(it.recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(38,-17)};settle()
        var title="";var offset=0
        fun snapshot(check:Boolean){scenario!!.onActivity{a->
            val manager=a.recycler.layoutManager as LinearLayoutManager;val first=manager.findFirstVisibleItemPosition();val v=manager.findViewByPosition(first)!!
            val name=v.findViewById<TextView>(R.id.file_title).text.toString()
            if(check){assertEquals(title,name);assertTrue("pixel offset preserved",kotlin.math.abs(offset-v.top)<=a.dp(1))}else{title=name;offset=v.top}
        }}
        snapshot(false);scenario!!.moveToState(Lifecycle.State.CREATED);scenario!!.moveToState(Lifecycle.State.RESUMED);settle();snapshot(true)
        scenario!!.recreate();settle();snapshot(true)
    }
    @Test fun fileInformationHasCompletePropertiesAndClickableLocation(){
        repeat(35){File(root,"doc-${it.toString().padStart(2,'0')}.txt").writeText("fixture")}
        val file=File(root,"doc-28.txt");launch();scenario!!.onActivity{fileInfo(it,file)};settle()
        onView(withId(R.id.file_info_name)).inRoot(isDialog()).check(matches(withText(file.name)))
        onView(withId(R.id.file_info_location)).inRoot(isDialog()).check(matches(withText(file.absolutePath)))
        onView(withText("可读：")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(R.id.file_info_close)).inRoot(isDialog()).check(matches(isDisplayed()));save("file-info")
        onView(withId(R.id.file_info_location)).inRoot(isDialog()).perform(click());settle()
        scenario!!.onActivity{a->
            val row=a.recycler.findViewHolderForAdapterPosition(29)?.itemView
            assertNotNull("position link reveals requested file",row);assertEquals(file.name,row!!.findViewById<TextView>(R.id.file_title).text.toString())
        }
    }
    @Test fun informationDismissedDuringEntranceAlwaysRemovesTheWindow(){
        val file=File(root,"fixture.txt").apply{writeText("fixture")};launch()
        repeat(3){
            val latch=CountDownLatch(1);var dialog:MotionDialog?=null
            scenario!!.onActivity{a->dialog=FileInfoPanel.show(a,file,"document","7 B"){};dialog!!.close{latch.countDown()}}
            assertTrue(latch.await(3,TimeUnit.SECONDS));scenario!!.onActivity{assertFalse(dialog!!.isShowing)}
        }
        onView(withId(R.id.native_list)).check(matches(isDisplayed()))
    }
    @Test fun mediaCategoriesUseDateGroupsSquareThumbnailsAndVideoDuration(){
        val now=System.currentTimeMillis()
        repeat(6){i->
            val file=File(root,"picture-$i.png")
            val bitmap=Bitmap.createBitmap(140,110,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(40+i*25,105+i*15,185-i*18))}
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            file.setLastModified(now-if(i<2)0 else 2*86400000L)
        }
        val video=File(root,"clip.mp4");ins.context.assets.open("media-fixture.mp4").use{input->video.outputStream().use{input.copyTo(it)}}
        assertTrue("real video metadata is readable",MediaFacts.duration(video)>0)
        launch(false);onView(withContentDescription("文件")).perform(click());settle()
        onView(withText("图片")).perform(click());settle()
        scenario!!.onActivity{a->
            val manager=a.recycler.layoutManager as GridLayoutManager;assertEquals(4,manager.spanCount)
            val tile=a.recycler.findViewHolderForAdapterPosition(2)!!.itemView.getChildAt(0)
            assertEquals(tile.width,tile.height);assertNotNull(tile.contentDescription)
        };save("pictures")
        androidx.test.espresso.Espresso.pressBack();settle();onView(withText("视频")).perform(click());settle();save("videos")
        scenario!!.onActivity{a->assertTrue(a.recycler.layoutManager is GridLayoutManager)}
    }
    private fun View.getChildAt(index:Int)=(this as ViewGroup).getChildAt(index)
}
