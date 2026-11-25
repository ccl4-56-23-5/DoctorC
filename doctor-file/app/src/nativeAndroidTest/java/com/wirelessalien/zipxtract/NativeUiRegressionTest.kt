package com.wirelessalien.zipxtract

import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.nativeui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeUiRegressionTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private lateinit var root:File
    private var scenario:ActivityScenario<MainActivity>?=null
    @Before fun setup(){
        for(cmd in listOf("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow","pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"))
            ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd)).use{it.readBytes()}
        root=File(context.getExternalFilesDir(null),"native-ui-${System.nanoTime()}").apply{mkdirs()}
        CreationOptions.retireStoredOptions(context)
        context.getSharedPreferences("native_ui",0).edit().putString("sort","name").putBoolean("descending",false).apply()
    }
    @After fun cleanup(){scenario?.close();CreationOptions.retireStoredOptions(context);root.deleteRecursively()}
    private fun launch(){
        val intent=Intent(context,MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DIRECTORY)
            .putExtra(MainActivity.EXTRA_DIRECTORY_PATH,root.path)
        scenario=ActivityScenario.launch(intent)
        waitRows()
    }
    private fun waitRows(){
        val end=android.os.SystemClock.elapsedRealtime()+7000
        while(android.os.SystemClock.elapsedRealtime()<end){
            ins.waitForIdleSync();var count=0;scenario?.onActivity{count=it.recycler.adapter!!.itemCount}
            if(count>2)return
            android.os.SystemClock.sleep(100)
        }
    }
    @Test fun longFileNameNeverOverlapsSelectionOrMetadata(){
        File(root,"000_2018-08-12-大学前的薄刀锋之行-非常长的中文文件名称-最终版本照片.jpg.zip").writeText("fixture")
        repeat(35){File(root,"文件_${it.toString().padStart(2,'0')}.txt").writeText("sample")}
        launch()
        fun geometry(){scenario!!.onActivity{a->
            val row=a.recycler.findViewHolderForAdapterPosition(1)!!.itemView
            val title=row.findViewById<TextView>(R.id.file_title);val sub=row.findViewById<TextView>(R.id.file_subtitle);val trailing=row.findViewById<View>(R.id.file_trailing)
            val t=Rect();val s=Rect();val e=Rect();title.getGlobalVisibleRect(t);sub.getGlobalVisibleRect(s);trailing.getGlobalVisibleRect(e)
            assertTrue("title has space reserved for selection",t.right<e.left)
            assertTrue("metadata is below title",t.bottom<=s.top)
            assertTrue(title.maxLines==2)
        }}
        geometry();scenario!!.onActivity{it.recycler.findViewHolderForAdapterPosition(1)!!.itemView.getChildAt(0).performLongClick()};ins.waitForIdleSync();geometry()
        saveScreenshot("selection-long-name")
    }
    @Test fun floatingFadeExistsOnlyAfterScrollingAndHasNoHeaderBox(){
        repeat(60){File(root,"文档_${it.toString().padStart(3,'0')}.txt").writeText("fixture")}
        launch()
        scenario!!.onActivity{a->
            val fade=a.findViewById<View>(R.id.native_top_fade)
            assertEquals(View.INVISIBLE,fade.visibility);assertNull(fade.background);assertEquals(0f,fade.elevation)
            val header=a.findViewById<View>(R.id.native_header);assertNull(header.background)
        };saveScreenshot("files-at-top")
        scenario!!.onActivity{it.recycler.scrollBy(0,it.dp(88))};ins.waitForIdleSync()
        scenario!!.onActivity{a->
            val large=a.findViewById<View>(R.id.native_large_title)
            val small=a.findViewById<View>(R.id.native_compact_title)
            assertEquals("large title exits before compact title appears",0f,large.alpha)
            assertTrue("compact controls now visible",(small.parent.parent as View).alpha>0f)
        }
        scenario!!.onActivity{it.recycler.scrollBy(0,it.dp(285))};ins.waitForIdleSync();android.os.SystemClock.sleep(250)
        scenario!!.onActivity{a->assertEquals(View.VISIBLE,a.findViewById<View>(R.id.native_top_fade).visibility);assertTrue(a.findViewById<View>(R.id.native_top_fade).alpha>.8f)}
        saveScreenshot("files-after-scroll")
        scenario!!.onActivity{it.recycler.scrollToPosition(0)};ins.waitForIdleSync();android.os.SystemClock.sleep(200)
        scenario!!.onActivity{assertEquals(View.INVISIBLE,it.findViewById<View>(R.id.native_top_fade).visibility)}
    }
    @Test fun creationUsesFreshOptionsAndCurrentSourceEveryTime(){
        val a=File(root,"第一份.txt").apply{writeText("one")}
        val other=File(root,"另一个目录").apply{mkdirs()};val b=File(other,"第二份.txt").apply{writeText("two")}
        launch();scenario!!.onActivity{it.showCreate(listOf(a.path))};settle()
        val dialog=androidx.test.espresso.matcher.RootMatchers.isDialog()
        onView(withId(R.id.archive_name)).inRoot(dialog).check(matches(withText(a.name)))
        onView(withId(R.id.archive_path)).inRoot(dialog).check(matches(withText(org.hamcrest.Matchers.containsString(root.name))))
        onView(withId(R.id.archive_format_zip)).inRoot(dialog).perform(click())
        onView(withId(R.id.archive_delete)).inRoot(dialog).perform(scrollTo(),click())
        onView(withContentDescription("返回")).inRoot(dialog).perform(click());settle()
        scenario!!.onActivity{it.showCreate(listOf(b.path))};settle()
        onView(withId(R.id.archive_name)).inRoot(dialog).check(matches(withText(b.name)))
        onView(withId(R.id.archive_path)).inRoot(dialog).check(matches(withText(org.hamcrest.Matchers.containsString(other.name))))
        onView(withId(R.id.archive_delete)).inRoot(dialog).check(matches(isNotChecked()))
        onView(withId(R.id.archive_password_toggle)).inRoot(dialog).check(matches(isNotChecked()))
        onView(withText(org.hamcrest.Matchers.containsString("已记忆"))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        onView(withText("无损压缩 · 完整校验")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        onView(withText(org.hamcrest.Matchers.containsString("除名称与路径外"))).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
        assertTrue(context.getSharedPreferences("native_creation_secure",0).all.isEmpty())
        saveScreenshot("create-clean")
    }
    @Test fun removedHistoryAndMemoryAreErasedAndClassesAreAbsent(){
        context.getSharedPreferences("native_tasks",0).edit().putString("items","old task").commit()
        context.getSharedPreferences("native_creation_secure",0).edit().putString("secret","old encrypted secret").putString("format","ZIP").commit()
        val alias="sevenzip.creation.password.v1"
        javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(android.security.keystore.KeyGenParameterSpec.Builder(alias,android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
        ActiveOperation.init(context)
        assertTrue(context.getSharedPreferences("native_tasks",0).all.isEmpty())
        assertTrue(context.getSharedPreferences("native_creation_secure",0).all.isEmpty())
        assertFalse(java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.containsAlias(alias))
        for(name in listOf("CreationMemory","TaskLedger"))assertThrows(ClassNotFoundException::class.java){Class.forName("com.wirelessalien.zipxtract.nativeui.$name")}
        assertEquals("FileDoctorC",context.applicationInfo.loadLabel(context.packageManager).toString())
        assertFalse(ActiveOperation.busy())
    }
    @Test fun noMaterialComponentsInRuntime(){
        assertThrows(ClassNotFoundException::class.java){Class.forName("com.google.android.material.button.MaterialButton")}
        assertFalse(com.wirelessalien.zipxtract.helper.SafeArchiveEngine.Options::class.java.declaredFields.any{it.name in listOf("level","compressionLevel","zipParameters")})
    }
    @Test fun generatedArtworkDecodesAsBitmapsAndOldLineIconsAreAbsent(){
        for(key in Art.keys){
            val art=Art.drawable(context,key)
            if(key=="rename"){
                assertTrue("rename is the sole local-clock Canvas exception",art is ClockRenameDrawable)
                val bitmap=android.graphics.Bitmap.createBitmap(192,192,android.graphics.Bitmap.Config.ARGB_8888)
                art.setBounds(0,0,192,192);art.draw(android.graphics.Canvas(bitmap))
                val pixels=IntArray(192*192);bitmap.getPixels(pixels,0,192,0,0,192,192)
                assertTrue("rename has a substantial solid silhouette",pixels.count{android.graphics.Color.alpha(it)>240}>1000)
                assertEquals("rename has no surrounding tile",0,android.graphics.Color.alpha(bitmap.getPixel(4,4)))
                assertTrue("rename is neutral, not the old colorful raster",pixels.filter{android.graphics.Color.alpha(it)>32}.all{
                    val channels=listOf(android.graphics.Color.red(it),android.graphics.Color.green(it),android.graphics.Color.blue(it))
                    channels.maxOrNull()!!-channels.minOrNull()!!<=10
                })
                bitmap.recycle()
            }else{
                assertTrue("$key is a packaged raster",art is android.graphics.drawable.BitmapDrawable)
                val bitmap=(art as android.graphics.drawable.BitmapDrawable).bitmap
                assertTrue("$key has pixels",bitmap.width>=96&&bitmap.height>=96)
                assertTrue("$key retains transparency",bitmap.hasAlpha())
            }
        }
        assertTrue("Renamer artwork is explicitly packaged", "rename" in Art.keys)
        assertEquals(31,Art.keys.size)
        assertThrows(ClassNotFoundException::class.java){Class.forName("com.wirelessalien.zipxtract.nativeui.LineIcon")}
    }
    private fun popupBounds(){
        android.os.SystemClock.sleep(400);ins.waitForIdleSync()
        onView(withId(R.id.native_popup_panel)).inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).check{v,e->
            if(e!=null)throw e
            val r=Rect();assertTrue(v.getGlobalVisibleRect(r))
            val screen=context.resources.displayMetrics
            assertTrue("panel stays inside horizontal bounds",r.left>=0&&r.right<=screen.widthPixels)
            assertTrue("panel stays inside vertical bounds",r.top>=0&&r.bottom<=screen.heightPixels)
            assertTrue("menu remains a compact popover",r.width()<screen.widthPixels*.9)
        }
    }
    @Test fun overflowMenuSupportsNestedSortingAndBackDismissal(){
        repeat(4){File(root,"文档_$it.txt").writeText("fixture")};launch()
        onView(org.hamcrest.Matchers.allOf(withContentDescription("更多选项"),isDisplayed())).perform(click())
        popupBounds();saveScreenshot("overflow-menu")
        onView(withText("排序方式")).perform(click());settle();popupBounds()
        onView(withText("日期")).perform(click());settle()
        assertEquals("date",context.getSharedPreferences("native_ui",0).getString("sort",null))
        onView(org.hamcrest.Matchers.allOf(withContentDescription("更多选项"),isDisplayed())).perform(click())
        androidx.test.espresso.Espresso.pressBack();settle();onView(withId(R.id.native_popup_panel)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
    }
    @Test fun directoryBookmarkSurvivesRelaunchAndCanBeRemovedFromTheList() {
        val folder = File(root, "Bookmark fixture").apply { mkdir() }
        val sample = File(folder, "bookmark-probe.txt").apply { writeText("fixture") }
        repeat(3) { File(root, "other-$it.txt").writeText("fixture") }
        val prefs = context.getSharedPreferences("native_ui", 0)
        fun options() {
            onView(org.hamcrest.Matchers.allOf(withContentDescription("更多选项"), isDisplayed())).perform(click())
            settle()
        }
        fun select(text: Int) {
            onView(withText(context.getString(text))).perform(click())
            settle()
        }
        try {
            launch()
            onView(withText(folder.name)).perform(click())
            settle()
            options()
            select(R.string.bookmark_add_current)
            assertTrue(folder.path in prefs.getStringSet("directory_bookmarks", emptySet()).orEmpty())

            scenario!!.close()
            launch()
            options()
            select(R.string.bookmarks)
            onView(withText(folder.name)).inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup())
                .perform(click())
            settle()
            onView(withText(sample.name)).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            settle()

            options()
            select(R.string.bookmarks)
            val storage = android.os.Environment.getExternalStorageDirectory().path
            val path = "内部存储" + folder.path.removePrefix(storage)
            onView(withContentDescription(context.getString(R.string.bookmark_remove_named, path))).perform(click())
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000
            fun saved() = folder.path in prefs.getStringSet("directory_bookmarks", emptySet()).orEmpty()
            while (saved() && android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(50)
            }
            assertFalse("removal commits when the popup finishes closing", saved())
            assertTrue(sample.isFile)
        } finally {
            val paths = prefs.getStringSet("directory_bookmarks", emptySet()).orEmpty()
            prefs.edit().putStringSet("directory_bookmarks", paths - folder.path).commit()
        }
    }
    @Test fun fileMenuNearBottomFitsAndRenameUsesRoundedPrompt(){
        repeat(40){File(root,"文档_${it.toString().padStart(2,'0')}.txt").writeText("fixture")};launch()
        scenario!!.onActivity{it.recycler.scrollToPosition(22)};ins.waitForIdleSync()
        scenario!!.onActivity{a->
            val manager=a.recycler.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
            val row=a.recycler.findViewHolderForAdapterPosition(manager.findLastVisibleItemPosition())!!.itemView
            row.findViewById<View>(R.id.file_trailing).performClick()
        }
        popupBounds();onView(withText("重命名")).perform(click());settle()
        onView(withText("取消")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());settle()
        onView(withId(R.id.native_list)).check(matches(isDisplayed()))
    }
    @Test fun tarMenuInsideCreateDialogUsesTheSamePopover(){
        val source=File(root,"示例.txt").apply{writeText("fixture")};launch()
        scenario!!.onActivity{it.showCreate(listOf(source.path))};ins.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        onView(withId(R.id.archive_format_tar)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
        onView(withText(org.hamcrest.Matchers.startsWith("选择 TAR 类型"))).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
        popupBounds();onView(withText("TAR.XZ")).inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click());settle()
        onView(withText("选择 TAR 类型 · TAR.XZ")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
        onView(withContentDescription("返回")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
    }
    private fun settle(){android.os.SystemClock.sleep(420);ins.waitForIdleSync()}
    private fun launchHome(){
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java));settle()
        onView(withContentDescription("文件")).perform(click());settle()
    }
    @Test fun sixCategoriesReturnToFilesHubInsteadOfCompression(){
        launchHome()
        for(name in listOf("文档","图片","视频","音乐","压缩包","安装包")){
            onView(org.hamcrest.Matchers.allOf(withText(name),isDisplayed())).perform(click());settle()
            onView(withId(R.id.native_large_title)).check(matches(withText(name)))
            androidx.test.espresso.Espresso.pressBack();settle()
            onView(withId(R.id.native_large_title)).check(matches(withText("浏览")))
        }
    }
    @Test fun directoryBackAscendsOneLevelAndArchiveReturnsToItsSource(){
        val child=File(root,"子目录").apply{mkdirs()};File(child,"示例.txt").writeText("fixture")
        java.util.zip.ZipOutputStream(File(root,"来源.zip").outputStream()).use{it.putNextEntry(java.util.zip.ZipEntry("inner.txt"));it.write("fixture".toByteArray());it.closeEntry()}
        launch();scenario!!.onActivity{it.openDirectory(child)};settle()
        androidx.test.espresso.Espresso.pressBack();settle();onView(withId(R.id.native_large_title)).check(matches(withText(root.name)))
        onView(withText("来源.zip")).perform(click());settle()
        onView(withId(R.id.native_large_title)).check(matches(withText("来源.zip")))
        androidx.test.espresso.Espresso.pressBack();settle();onView(withId(R.id.native_large_title)).check(matches(withText(root.name)))
    }
    @Test fun dockFloatsAtTopAndDraggingMovesSelectorAndAmplifiesGlass(){
        launchHome()
        var restElevation=0f;var down=0L
        scenario!!.onActivity{a->
            val dock=a.findViewById<GestureDock>(R.id.native_dock)
            assertTrue("always floating at top",dock.elevation>0f);assertNotNull(dock.background)
            val selector=a.findViewById<View>(R.id.native_dock_selector)
            assertTrue("content must sit above the glass highlight",(selector.parent as ViewGroup).getChildAt(1).elevation>selector.elevation)
            restElevation=dock.elevation
            val x=selector.translationX
            down=android.os.SystemClock.uptimeMillis()
            fun send(action:Int,px:Float){android.view.MotionEvent.obtain(down,down,action,px,dock.height/2f,0).also{dock.dispatchTouchEvent(it);it.recycle()}}
            send(android.view.MotionEvent.ACTION_DOWN,dock.width*.75f)
            send(android.view.MotionEvent.ACTION_MOVE,dock.width*.28f)
            assertTrue("selector follows the finger",selector.translationX<x)
        }
        // Strength now animates over 180ms; checking in the MOVE callback only sees its initial frame.
        android.os.SystemClock.sleep(260);ins.waitForIdleSync()
        scenario!!.onActivity{a->
            val dock=a.findViewById<GestureDock>(R.id.native_dock)
            val selector=a.findViewById<View>(R.id.native_dock_selector)
            assertTrue("selected lens grows without scaling labels",selector.scaleX>1.02f)
            assertEquals(1f,dock.scaleX);assertEquals(1f,dock.scaleY)
            assertTrue("glass shadow grows during drag",dock.elevation>restElevation)
            android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,dock.width*.28f,dock.height/2f,0).also{dock.dispatchTouchEvent(it);it.recycle()}
        };settle()
        onView(withId(R.id.native_large_title)).check(matches(withText("压缩")))
        onView(withId(R.id.native_dock)).perform(swipeRight());settle()
        onView(withId(R.id.native_large_title)).check(matches(withText("浏览")))
        saveScreenshot("floating-dock")
    }
    @Test fun motionDialogActuallyAnimatesEnterAndBackExit(){
        File(root,"示例.txt").writeText("fixture");launch()
        val entered=java.util.concurrent.CountDownLatch(1);val exited=java.util.concurrent.CountDownLatch(1)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        scenario!!.onActivity{a->
            val content=android.widget.FrameLayout(a).apply{minimumHeight=a.dp(200)}
            val d=MotionDialog(a,R.style.NativeTheme,true).apply{motionContent=content;setContentView(content)}
            d.show()
            content.postDelayed({
                try{assertEquals(1f,content.alpha);assertEquals(1f,content.scaleX);assertTrue("opaque page enters on integral pixels",content.translationX>0f&&content.translationX==content.translationX.toInt().toFloat())}catch(t:Throwable){failure.set(t)}finally{entered.countDown()}
                content.postDelayed({
                    d.onBackPressed()
                    content.postDelayed({
                        try{assertTrue("window remains during exit",d.isShowing);assertEquals(1f,content.alpha);assertEquals(1f,content.scaleX);assertTrue(content.translationX>0f)}catch(t:Throwable){failure.set(t)}finally{exited.countDown()}
                    },65)
                },350)
            },70)
        }
        assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));assertTrue(exited.await(3,java.util.concurrent.TimeUnit.SECONDS));settle();failure.get()?.let{throw it}
    }
    @Test fun actualPopoverSurfaceAnimatesEnterAndOutsideDismiss(){
        File(root,"示例.txt").writeText("fixture");launch()
        val sampled=java.util.concurrent.CountDownLatch(1);val closed=java.util.concurrent.CountDownLatch(1)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        scenario!!.onActivity{a->
            val menus=NativePopups(a);menus.show("排序",listOf("名称","日期")){}
            val field=NativePopups::class.java.getDeclaredField("active").apply{isAccessible=true}
            val popup=field.get(menus) as MotionPopup;val content=popup.motionSurface
            content.postDelayed({
                try{assertEquals(1f,content.alpha);assertTrue("shape expands continuously",content.progress>0f&&content.progress<1f);assertTrue(content.currentBounds.width()>content.originBounds.width());assertTrue(content.currentBounds.width()<content.targetBounds.width())}catch(t:Throwable){failure.set(t)}finally{sampled.countDown()}
                content.postDelayed({popup.dismiss();content.postDelayed({
                    try{assertTrue(popup.isShowing);assertTrue("shape contracts continuously",content.progress>0f&&content.progress<1f)}catch(t:Throwable){failure.set(t)}finally{closed.countDown()}
                },65)},350)
            },70)
        }
        assertTrue(sampled.await(3,java.util.concurrent.TimeUnit.SECONDS));assertTrue(closed.await(3,java.util.concurrent.TimeUnit.SECONDS));settle();failure.get()?.let{throw it}
    }
    private fun saveScreenshot(name:String){
        ins.waitForIdleSync();val b=ins.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null),"native-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
    private fun View.getChildAt(index:Int):View=(this as ViewGroup).getChildAt(index)
}
