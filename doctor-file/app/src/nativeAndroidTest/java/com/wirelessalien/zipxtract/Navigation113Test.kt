package com.wirelessalien.zipxtract

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real home/category/dock handlers, with disposable archives and source directories only. */
@RunWith(AndroidJUnit4::class)
class Navigation113Test {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private val prefs get()=context.getSharedPreferences("native_ui",0)
    private var saved:Map<String,* > = emptyMap<String,Any>()
    private var scenario:ActivityScenario<MainActivity>?=null
    private lateinit var root:File
    private lateinit var archive:File
    @Before fun setup(){
        saved=prefs.all.toMap()
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")).use{it.readBytes()}
        root=File(context.getExternalFilesDir(null),"navigation113-${System.nanoTime()}").apply{mkdirs()}
        File(root,"ordinary.txt").writeText("original file")
        archive=File(root,"navigation.zip")
        ZipOutputStream(archive.outputStream()).use{zip->
            for(name in listOf("folder/inner.txt","top.txt")){
                zip.putNextEntry(ZipEntry(name));zip.write("archive payload".toByteArray());zip.closeEntry()
            }
        }
    }
    @After fun cleanup(){
        scenario?.close();root.deleteRecursively()
        prefs.edit().clear().apply{saved.forEach{(key,value)->when(value){
            is String->putString(key,value);is Boolean->putBoolean(key,value);is Int->putInt(key,value)
            is Long->putLong(key,value);is Float->putFloat(key,value)
            is Set<*>->putStringSet(key,value.filterIsInstance<String>().toSet())
        }}}.commit()
    }
    private fun <T> main(block:()->T):T{
        var result:Result<T>?=null;ins.runOnMainSync{result=runCatching(block)};return result!!.getOrThrow()
    }
    private fun field(a:MainActivity,name:String):Any?=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun invoke(a:MainActivity,name:String,vararg args:Any?){
        val method=MainActivity::class.java.declaredMethods.single{it.name==name&&it.parameterTypes.size==args.size}
        method.isAccessible=true;method.invoke(a,*args)
    }
    private fun await(label:String,condition:()->Boolean){
        val deadline=SystemClock.uptimeMillis()+9000
        while(SystemClock.uptimeMillis()<deadline){if(main{runCatching(condition).getOrDefault(false)})return;SystemClock.sleep(60)}
        fail("Timed out: $label")
    }
    private fun all(root:View):List<View> = buildList{
        fun visit(view:View){add(view);if(view is ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i))};visit(root)
    }
    private fun click(text:String?=null,description:String?=null){
        lateinit var target:View
        await("visible control ${text?:description}"){
            var found:View?=null
            scenario!!.onActivity{a->found=all(a.window.decorView).firstOrNull{it.isShown&&
                (text==null||it is TextView&&it.text.toString()==text)&&
                (description==null||it.contentDescription?.toString()==description)}}
            found?.also{target=it}!=null
        }
        main{
            var clickable=target
            while(!clickable.isClickable)clickable=clickable.parent as? View?:throw AssertionError("Not clickable")
            assertTrue(clickable.performClick())
        }
        SystemClock.sleep(380)
    }
    private fun expect(page:MainActivity.Page){await("page $page"){
        var actual:Any?=null;scenario!!.onActivity{actual=field(it,"page")};actual==page
    }}
    private fun launch(home:Boolean=false){
        val intent=Intent(context,MainActivity::class.java)
        if(!home)intent.setAction(MainActivity.ACTION_OPEN_DIRECTORY).putExtra(MainActivity.EXTRA_DIRECTORY_PATH,root.path)
        scenario=ActivityScenario.launch(intent);expect(if(home)MainActivity.Page.ARCHIVES else MainActivity.Page.DIRECTORY)
    }
    private fun expectRealDirectory(){
        expect(MainActivity.Page.DIRECTORY)
        scenario!!.onActivity{a->assertEquals(root,field(a,"directory"));assertNull(field(a,"archive"))}
        await("real file row"){
            var found=false;scenario!!.onActivity{a->found=all(a.recycler).filterIsInstance<TextView>().any{it.text.toString()=="ordinary.txt"}};found
        }
    }
    @Test fun homeExtractPickerCannotHijackFileTab(){
        launch(true);click("解压文件");expect(MainActivity.Page.CATEGORY)
        click(description="文件");expect(MainActivity.Page.HUB)
        repeat(3){click(description="压缩");expect(MainActivity.Page.ARCHIVES);click(description="文件");expect(MainActivity.Page.HUB)}
    }
    @Test fun extractionPickerPreservesLastRealDirectoryAcrossRepeatedTabSwitches(){
        launch();click(description="压缩");expect(MainActivity.Page.ARCHIVES)
        repeat(3){
            click("解压文件");expect(MainActivity.Page.CATEGORY)
            click(description="文件");expectRealDirectory()
            click(description="压缩");expect(MainActivity.Page.ARCHIVES)
        }
    }
    @Test fun previewBackAndTabChangesStayInRealFileBrowser(){
        launch();click("navigation.zip");expect(MainActivity.Page.ARCHIVE)
        click("folder")
        await("inner archive folder"){
            var prefix:Any?=null;scenario!!.onActivity{prefix=field(it,"archivePrefix")};prefix=="folder/"
        }
        scenario!!.onActivity{it.onBackPressed()};expect(MainActivity.Page.ARCHIVE)
        scenario!!.onActivity{it.onBackPressed()};expectRealDirectory()
        click(description="压缩");click(description="文件");expectRealDirectory()
    }
    @Test fun previewsOpenedFromArchivePickerCannotReplaceTheFileBrowser(){
        launch(true);click("解压文件");expect(MainActivity.Page.CATEGORY)
        scenario!!.onActivity{invoke(it,"openArchive",archive,null)};expect(MainActivity.Page.ARCHIVE)
        scenario!!.onActivity{invoke(it,"switchTab",0)};expect(MainActivity.Page.ARCHIVES)
        click(description="文件");expect(MainActivity.Page.HUB)
        scenario!!.onActivity{assertNull(field(it,"archive"));assertTrue((field(it,"entries") as List<*>).isEmpty())}
    }
    @Test fun changingTabsDuringArchiveReadCannotReopenAnOldPreview(){
        val large=File(root,"many.zip")
        ZipOutputStream(large.outputStream()).use{zip->repeat(5000){i->zip.putNextEntry(ZipEntry("folder/item-$i.txt"));zip.write(i);zip.closeEntry()}}
        launch()
        scenario!!.onActivity{invoke(it,"openArchive",large,null)}
        SystemClock.sleep(15)
        scenario!!.onActivity{invoke(it,"switchTab",0);invoke(it,"switchTab",1)}
        expectRealDirectory();SystemClock.sleep(450);expectRealDirectory()
    }
    @Test fun recreatingPreviewRestoresARecoverableSourcePage(){
        launch();click("navigation.zip");expect(MainActivity.Page.ARCHIVE)
        scenario!!.recreate();expectRealDirectory()
        click(description="压缩");click(description="文件");expectRealDirectory()
    }
    @Test fun anOldPreviewOriginCannotRestoreAnArchiveWithoutItsFile(){
        launch();click("navigation.zip");expect(MainActivity.Page.ARCHIVE)
        // A notification/intent may open an output directory while the preview is still live.
        scenario!!.onActivity{it.openDirectory(root)};expect(MainActivity.Page.DIRECTORY)
        click(description="压缩");click(description="文件");expectRealDirectory()
        scenario!!.onActivity{it.openDirectory(android.os.Environment.getExternalStorageDirectory())}
        expect(MainActivity.Page.DIRECTORY)
        scenario!!.onActivity{it.onBackPressed()};expect(MainActivity.Page.HUB)
        click(description="压缩");click(description="文件");expect(MainActivity.Page.HUB)
    }
}
