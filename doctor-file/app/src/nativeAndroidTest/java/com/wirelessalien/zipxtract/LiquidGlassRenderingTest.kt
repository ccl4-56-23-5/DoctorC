package com.wirelessalien.zipxtract

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.activity.MainActivity
import com.wirelessalien.zipxtract.nativeui.GestureDock
import com.wirelessalien.zipxtract.nativeui.LiquidGlassView
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Test-only stripe drawing verifies background coordinate remapping, not just shader compilation. */
@RunWith(AndroidJUnit4::class)
class LiquidGlassRenderingTest{
    private val ins=InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario:ActivityScenario<MainActivity>

    @Before fun setup(){
        assumeTrue("Optical path requires Android 13+",Build.VERSION.SDK_INT>=33)
        val context=ins.targetContext
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")).use{it.readBytes()}
        scenario=ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(650);ins.waitForIdleSync()
    }
    @After fun cleanup(){if(::scenario.isInitialized)scenario.close()}
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(a:MainActivity,name:String):T=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a) as T
    private fun bounds(v:View):Rect{val xy=IntArray(2);v.getLocationOnScreen(xy);return Rect(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height)}
    private fun screenshot():Bitmap=ins.uiAutomation.takeScreenshot()?:throw AssertionError("Hardware screenshot unavailable")
    private fun settle(){SystemClock.sleep(140);ins.waitForIdleSync()}
    private fun dominant(color:Int):Int=Color.red(color)-(Color.green(color)+Color.blue(color))/2

    @Test fun gpuLensRemapsBothBevelsAcrossRealBackgroundStripes(){
        var fixture:View?=null;var target:ViewGroup?=null
        lateinit var dock:GestureDock;lateinit var search:LiquidGlassView;lateinit var rect:Rect
        var surfacesReady=false
        var before:Bitmap?=null;var after:Bitmap?=null
        var density=1f
        try{
            scenario.onActivity{a->
                dock=field(a,"dock");search=field(a,"searchDock");target=field(a,"target")
                surfacesReady=true
                density=a.resources.displayMetrics.density
                rect=bounds(dock);assertTrue("dock laid out",rect.width()>100&&rect.height()>40)
                val sourceBounds=bounds(target!!)
                // Use color bands wider than the 16 dp backdrop blur. The boundary lies
                // within the Haze rim's 20 dp refraction profile on both ends.
                val stripeWidth=(48*density).roundToInt()
                val edgeInset=(9*density).roundToInt()
                val leftPhase=rect.left-sourceBounds.left+edgeInset
                val rightPhase=rect.right-sourceBounds.left-edgeInset
                val split=(leftPhase+rightPhase)/2
                fixture=object:View(a){
                    private val paint=Paint()
                    override fun onDraw(canvas:Canvas){
                        fun stripes(start:Int,end:Int,phase:Int){
                            val saved=canvas.save();canvas.clipRect(start,0,end,height)
                            var index=floor((start-phase)/stripeWidth.toDouble()).toInt()
                            var x=index*stripeWidth+phase
                            while(x<end){
                                paint.color=if((index and 1)==0)Color.RED else Color.CYAN
                                canvas.drawRect(x.toFloat(),0f,(x+stripeWidth).toFloat(),height.toFloat(),paint)
                                x+=stripeWidth
                                index++
                            }
                            canvas.restoreToCount(saved)
                        }
                        stripes(0,split,leftPhase);stripes(split,width,rightPhase)
                    }
                }
                target!!.addView(fixture,FrameLayout.LayoutParams(-1,-1))
                dock.visibility=View.INVISIBLE;search.visibility=View.INVISIBLE
            }
            settle();before=screenshot()
            scenario.onActivity{dock.visibility=View.VISIBLE;search.visibility=View.VISIBLE;dock.invalidate();search.invalidate()}
            settle();after=screenshot()
            scenario.onActivity{
                assertTrue("dock GPU lens inactive: ${dock.liquidGlassFailure}",dock.isLiquidGlassRendering)
                assertTrue("search GPU lens inactive: ${search.liquidGlassFailure}",search.isLiquidGlassRendering)
                assertNull(dock.liquidGlassFailure);assertNull(search.liquidGlassFailure)
            }
            fun crossed(left:Boolean):Set<Int>{
                val changed=mutableSetOf<Int>();var clearSamples=0
                for(distance in (2*density).roundToInt()..(7*density).roundToInt())for(dy in -2..2){
                    val x=if(left)rect.left+distance else rect.right-1-distance
                    val y=rect.centerY()+dy
                    val original=dominant(before!!.getPixel(x,y));val optical=dominant(after!!.getPixel(x,y))
                    if(abs(original)>=180){
                        clearSamples++
                        // Equal-channel tint, Gaussian frosting and white highlights cannot swap red/cyan sign.
                        // Light-mode grading compresses channel contrast before the tint.
                        if(abs(optical)>=22&&original*optical<0)changed.add(distance)
                    }
                }
                assertTrue("baseline must contain clean stripe samples",clearSamples>=50)
                return changed
            }
            val left=crossed(true);val right=crossed(false)
            assertTrue("left bevel did not remap background stripe classes: changed=$left",left.size>=2)
            assertTrue("right bevel did not remap background stripe classes: changed=$right",right.size>=2)
        }finally{
            before?.recycle();after?.recycle()
            if(::scenario.isInitialized)scenario.onActivity{target?.removeView(fixture);if(surfacesReady){dock.visibility=View.VISIBLE;search.visibility=View.VISIBLE}}
        }
    }

    @Test fun draggingKeepsHazeMaterialAndForegroundGeometry(){
        lateinit var dock:GestureDock;lateinit var content:ViewGroup
        var textBefore=emptyList<List<Any>>()
        fun foreground(v:View):List<List<Any>> = buildList{
            fun visit(child:View){
                if(child is TextView||child is ImageView)add(listOf(child.javaClass.simpleName,child.left,child.top,child.width,child.height,child.scaleX,child.scaleY))
                if(child is ViewGroup)for(i in 0 until child.childCount)visit(child.getChildAt(i))
            }
            visit(v)
        }
        scenario.onActivity{a->
            dock=field(a,"dock");content=field(a,"dockContent")
            assertEquals(0f, dock.selectionRefractionPx)
            textBefore=foreground(content)
            assertTrue("foreground exists",textBefore.isNotEmpty())
            val now=SystemClock.uptimeMillis()
            for((action,x) in listOf(android.view.MotionEvent.ACTION_DOWN to .25f,android.view.MotionEvent.ACTION_MOVE to .475f)){
                android.view.MotionEvent.obtain(now,now,action,dock.width*x,dock.height*.5f,0).also{dock.dispatchTouchEvent(it);it.recycle()}
            }
        }
        SystemClock.sleep(260);ins.waitForIdleSync()
        scenario.onActivity{
            assertEquals("drag keeps the same Haze refraction profile", 0f, dock.selectionRefractionPx)
            assertTrue("actual GPU path inactive: ${dock.liquidGlassFailure}",dock.isLiquidGlassRendering)
            assertNull(dock.liquidGlassFailure)
            assertEquals("foreground layout changed during optical drag",textBefore,foreground(content))
            for(item in foreground(content)){assertEquals(1f,item[5]);assertEquals(1f,item[6])}
            assertEquals(1f,dock.scaleX);assertEquals(1f,dock.scaleY)
            val now=SystemClock.uptimeMillis()
            android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_CANCEL,0f,0f,0).also{dock.dispatchTouchEvent(it);it.recycle()}
        }
    }

    @Test fun glassBodyGaussianSuppressesFineBackgroundDetailOnTheActualGpu(){
        lateinit var dock:GestureDock;lateinit var dockHost:View;lateinit var target:ViewGroup;lateinit var rect:Rect
        var fixture:View?=null;var before:Bitmap?=null;var after:Bitmap?=null
        val visibility=mutableListOf<Int>()
        fun detail(bitmap:Bitmap):Double {
            val values=(rect.left+rect.width()/3 until rect.right-rect.width()/3)
                .map { Color.red(bitmap.getPixel(it,rect.centerY())) }
            val average=values.average()
            return values.map { abs(it-average) }.average()
        }
        fun waitForFrame(message: String, valid: (Bitmap)->Boolean): Bitmap {
            val until = SystemClock.uptimeMillis() + 5_000
            do {
                settle()
                val frame = screenshot()
                if (valid(frame)) return frame
                frame.recycle()
            } while (SystemClock.uptimeMillis() < until)
            throw AssertionError(message)
        }
        try{
            scenario.onActivity{a->
                dock=field(a,"dock");dockHost=field(a,"dockHost");target=field(a,"target");rect=bounds(dock)
                fixture=object:View(a){
                    private val paint=Paint()
                    override fun onDraw(canvas:Canvas){
                        var x=0
                        while(x<width){paint.color=if((x/3)%2==0)Color.BLACK else Color.WHITE
                            canvas.drawRect(x.toFloat(),0f,(x+3).toFloat(),height.toFloat(),paint);x+=3}
                    }
                }
                target.addView(fixture,FrameLayout.LayoutParams(-1,-1))
                for(i in 0 until dock.childCount){visibility.add(dock.getChildAt(i).visibility);dock.getChildAt(i).visibility=View.INVISIBLE}
                dockHost.visibility=View.INVISIBLE
            }
            before = waitForFrame("The unoccluded 3 px black/white fixture never reached the display") {
                detail(it) > 100
            }
            scenario.onActivity{dockHost.visibility=View.VISIBLE;dock.invalidate()}
            val original=detail(checkNotNull(before))
            after = waitForFrame("Fine stripe contrast persisted after the glass frame was rendered") {
                detail(it) < original * .18
            }
            scenario.onActivity {
                assertTrue("Gaussian must run on the GPU lens path", dock.isLiquidGlassRendering)
                assertNull(dock.liquidGlassFailure)
            }
        }finally{
            before?.recycle();after?.recycle()
            scenario.onActivity{fixture?.let{target.removeView(it)};dockHost.visibility=View.VISIBLE
                visibility.forEachIndexed{i,value->dock.getChildAt(i).visibility=value}}
        }
    }
}
