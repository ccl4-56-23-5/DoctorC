package com.wirelessalien.zipxtract

import android.content.Intent
import android.graphics.Bitmap
import java.io.File
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.nativeui.GestureDock
import com.wirelessalien.zipxtract.nativeui.LiquidGlassView
import com.wirelessalien.zipxtract.nativeui.dp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real stationary input, not a direct onDrag call: regresses the missing hold state. */
@RunWith(AndroidJUnit4::class)
class Glass107RegressionTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario:ActivityScenario<MainActivity>
    private var downTime=0L
    private lateinit var folder:File
    @Before fun setup(){
        val context=ins.targetContext
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")).use{it.readBytes()}
        folder=File(context.getExternalFilesDir(null),"glass107-${System.nanoTime()}").apply{mkdirs()}
        repeat(40){File(folder,"中文 +%20%25 😀 #?_${it.toString().padStart(2,'0')}.txt").writeText("fixture")}
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DIRECTORY).putExtra(MainActivity.EXTRA_DIRECTORY_PATH,folder.path))
        settle(650)
    }
    @After fun cleanup(){if(::scenario.isInitialized)scenario.close();if(::folder.isInitialized){check(folder.name.startsWith("glass107-"));folder.deleteRecursively()}}
    private fun save(name:String){
        val bitmap=ins.uiAutomation.takeScreenshot()?:throw AssertionError("hardware screenshot unavailable")
        val dir=ins.targetContext.getExternalFilesDir("qa107")!!;dir.mkdirs()
        File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun settle(ms:Long=400){SystemClock.sleep(ms);ins.waitForIdleSync()}
    private fun field(a:MainActivity,name:String):Any?=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun strength(a:MainActivity)=(field(a,"dockStrength") as Float)
    private fun event(dock:GestureDock,action:Int,x:Float=dock.width*.25f,y:Float=dock.height*.5f){
        if(action==MotionEvent.ACTION_DOWN)downTime=SystemClock.uptimeMillis()
        MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,x,y,0).also{dock.dispatchTouchEvent(it);it.recycle()}
    }
    @Test fun stationaryHoldStrengthensAndReleaseDoesNotNavigate(){
        var page:Any?=null
        save("glass107-idle")
        scenario.onActivity{a->page=field(a,"page");event(a.findViewById(R.id.native_dock),MotionEvent.ACTION_DOWN)}
        settle(ViewConfiguration.getLongPressTimeout().toLong()+350)
        scenario.onActivity{a->
            val dock=a.findViewById<GestureDock>(R.id.native_dock)
            assertTrue("stationary hold must reach strong optics",strength(a)>.85f)
            assertEquals("dock layout itself must stay fixed",1f,dock.scaleX)
            assertEquals(1f,dock.scaleY)
            assertTrue("selected lens may bulge beyond capsule",dock.selectionOverflowEnabled)
            assertFalse("outline cannot clip the bulge",dock.clipToOutline)
            val selector=field(a,"dockSelector") as View
            assertTrue("held selection must visibly grow",selector.scaleY>1.1f)
            assertEquals(page,field(a,"page"))
            if(Build.VERSION.SDK_INT>=33){assertTrue("GPU optics inactive: ${dock.liquidGlassFailure}",dock.isLiquidGlassRendering);assertNull(dock.liquidGlassFailure)}
        }
        save("glass107-held")
        scenario.onActivity{a->event(a.findViewById(R.id.native_dock),MotionEvent.ACTION_UP)}
        settle()
        scenario.onActivity{a->assertEquals(page,field(a,"page"));assertTrue("release restores material",strength(a)<.02f)}
    }
    @Test fun cancelledHoldRestoresMaterialAndSelectedPage(){
        var page:Any?=null
        scenario.onActivity{a->page=field(a,"page");event(a.findViewById(R.id.native_dock),MotionEvent.ACTION_DOWN)}
        settle(ViewConfiguration.getLongPressTimeout().toLong()+350)
        scenario.onActivity{a->assertTrue(strength(a)>.85f);event(a.findViewById(R.id.native_dock),MotionEvent.ACTION_CANCEL)}
        settle()
        scenario.onActivity{a->assertEquals(page,field(a,"page"));assertTrue("cancel restores material",strength(a)<.02f)}
    }
    @Test fun paddingDownIsConsumedAndItsHoldAlwaysReceivesRelease(){
        var page:Any?=null
        scenario.onActivity{a->
            val dock=a.findViewById<GestureDock>(R.id.native_dock);page=field(a,"page")
            downTime=SystemClock.uptimeMillis()
            MotionEvent.obtain(downTime,downTime,MotionEvent.ACTION_DOWN,1f,1f,0).also{
                assertTrue("tracking DOWN must be accepted even outside a child",dock.dispatchTouchEvent(it));it.recycle()
            }
        }
        settle(ViewConfiguration.getLongPressTimeout().toLong()+350)
        scenario.onActivity{a->assertTrue(strength(a)>.85f);event(a.findViewById(R.id.native_dock),MotionEvent.ACTION_UP,1f,1f)}
        settle()
        scenario.onActivity{a->assertEquals(page,field(a,"page"));assertTrue(strength(a)<.02f)}
    }
    @Test fun extraPointerCancelsHoldAndRemainingUpCannotNavigate(){
        var page:Any?=null
        scenario.onActivity{a->page=field(a,"page");event(a.findViewById(R.id.native_dock),MotionEvent.ACTION_DOWN)}
        settle(ViewConfiguration.getLongPressTimeout().toLong()+350)
        scenario.onActivity{a->
            val dock=a.findViewById<GestureDock>(R.id.native_dock);assertTrue(strength(a)>.85f)
            val properties=Array(2){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coords=Array(2){i->MotionEvent.PointerCoords().apply{x=dock.width*(.25f+.5f*i);y=dock.height*.5f;pressure=1f;size=1f}}
            for(action in listOf(MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP)){
                MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0).also{dock.dispatchTouchEvent(it);it.recycle()}
            }
            event(dock,MotionEvent.ACTION_UP)
        }
        settle()
        scenario.onActivity{a->assertEquals(page,field(a,"page"));assertTrue(strength(a)<.02f)}
    }
    @Test fun diagonalMotionBeyondSlopCannotBecomeStationaryHold(){
        var page:Any?=null
        scenario.onActivity{a->
            val dock=a.findViewById<GestureDock>(R.id.native_dock);page=field(a,"page")
            val slop=ViewConfiguration.get(a).scaledTouchSlop.toFloat()
            event(dock,MotionEvent.ACTION_DOWN)
            event(dock,MotionEvent.ACTION_MOVE,dock.width*.25f+slop*2.2f,dock.height*.5f+slop*2f)
        }
        settle(ViewConfiguration.getLongPressTimeout().toLong()+350)
        scenario.onActivity{a->assertTrue("moving finger must not trigger long press",strength(a)<.02f);event(a.findViewById(R.id.native_dock),MotionEvent.ACTION_UP)}
        settle()
        scenario.onActivity{a->assertEquals(page,field(a,"page"));assertTrue(strength(a)<.02f)}
    }
    private fun glasses(root:View):List<LiquidGlassView>{
        val list=mutableListOf<LiquidGlassView>()
        fun visit(v:View){if(v is LiquidGlassView)list.add(v);if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))}
        visit(root);return list
    }
    @Test fun topGlassButtonsAreTrueCirclesAndInheritTheCompositedHeader(){
        // The shader refracts the composited blur. The raw-content hole is checked by Ui108RegressionTest.
        fun check(a:MainActivity){
            val buttons=glasses(a.window.decorView).filter{it.id!=R.id.native_dock&&it.id!=R.id.native_search_dock&&it.width>0&&it.isShown&&it.y<a.resources.displayMetrics.heightPixels*.4f}
            assertTrue("top toolbar needs independent glass buttons",buttons.size>=2)
            for(button in buttons){
                assertEquals("top glass must be circular",button.width,button.height)
                assertEquals(1f,button.scaleX);assertEquals(1f,button.scaleY)
                assertTrue("header must inherit the already-composited blur",button.inheritsBackdrop)
                if (Build.VERSION.SDK_INT >= 33 && button.isGlassSurfaceVisible) {
                    assertTrue("toolbar lens not drawn: ${button.liquidGlassFailure}", button.isLiquidGlassRendering)
                }
                assertNull(button.liquidGlassFailure)
            }
        }
        scenario.onActivity{check(it)}
        scenario.onActivity{it.recycler.scrollBy(0,it.dp(160))}
        settle()
        scenario.onActivity{check(it)}
    }
}
