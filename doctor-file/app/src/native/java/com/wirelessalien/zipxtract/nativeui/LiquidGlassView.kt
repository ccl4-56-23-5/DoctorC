/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import eightbitlab.com.blurview.BlurView
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Refracts only a separate content target. Child labels/icons are drawn afterwards, untouched.
 * API 24–32 and software snapshots retain BlurView's established compatibility path.
 */
open class LiquidGlassView(context:Context):BlurView(context){
    private var backdrop:View?=null
    private var renderer:LiquidLensRenderer?=null
    private var material=LiquidGlassOptics()
    private var selectionLens:WeakReference<View>?=null
    private val selectionBounds=RectF()
    private val compatibilityClip=android.graphics.Path()
    var selectionOverflowEnabled=false
        set(value){
            if(field==value)return
            field=value;material=material.copy(selectionOverflow=value)
            clipToOutline=!value;clipChildren=!value;clipToPadding=!value;invalidate()
        }
    private val ownLocation=IntArray(2)
    private val sourceLocation=IntArray(2)
    private var previousX=Int.MIN_VALUE
    private var previousY=Int.MIN_VALUE
    var isLiquidGlassRendering=false;private set
    var liquidGlassFailure:String?=null;private set
    internal val selectionRefractionPx: Float get() =
        if (material.style == LiquidGlassStyle.DEFAULT) resources.displayMetrics.density * material.selectionRefractionDp else 0f
    /** Toolbar mode samples the already blurred header and has a source-over fallback. */
    var inheritsBackdrop=false;private set
    var glassSurfaceOpacity=1f;private set
    val isGlassSurfaceVisible:Boolean get()=glassSurfaceOpacity>0f
    fun setInheritedBackdrop(opacity:Float){
        if(!inheritsBackdrop){inheritsBackdrop=true;setBlurEnabled(false)}
        val next=if(opacity.isFinite())opacity.coerceIn(0f,1f)else 0f
        if (glassSurfaceOpacity != next || material.opacity != next) {
            glassSurfaceOpacity = next
            material = material.copy(opacity = next)
            invalidate()
        }
    }

    private val positionListener=ViewTreeObserver.OnPreDrawListener{
        backdrop?.let{source->
            getLocationOnScreen(ownLocation);source.getLocationOnScreen(sourceLocation)
            val x=ownLocation[0]-sourceLocation[0];val y=ownLocation[1]-sourceLocation[1]
            if(x!=previousX||y!=previousY){previousX=x;previousY=y;invalidate()}
        }
        updateSelectionLens()
        true
    }

    private fun isIndependentSource(source:View):Boolean{
        if (source === this) return false
        var ancestor:android.view.ViewParent?=parent
        while(ancestor!=null){if(ancestor===source)return false;ancestor=ancestor.parent}
        return true
    }
    fun bindGlassTarget(source:View){
        // Validate again at draw time as a control may be reparented after it is bound.
        require(isIndependentSource(source)){"Glass source must exclude the glass subtree"}
        backdrop=source;previousX=Int.MIN_VALUE;invalidate()
    }
    internal fun setGlassMaterial(strength:Float,dark:Boolean,style:LiquidGlassStyle=LiquidGlassStyle.DEFAULT){
        val level=(strength.coerceIn(0f,1f)*60).roundToInt()/60f
        if(material.strength==level&&material.dark==dark&&material.style==style)return
        material=material.copy(strength=level,dark=dark,style=style);invalidate()
    }
    fun setSelectionLens(view:View){
        if(view.parent!==this)return
        selectionLens=WeakReference(view)
        updateSelectionLens()
    }
    private fun updateSelectionLens(){
        val view=selectionLens?.get()
        val visible=view!=null&&view.parent===this&&view.visibility==View.VISIBLE&&view.width>0&&view.height>0
        selectionBounds.setEmpty()
        if(visible){
            // Includes pivot, scale and translation without changing the child's layout bounds.
            selectionBounds.set(0f,0f,view!!.width.toFloat(),view.height.toFloat())
            view.matrix.mapRect(selectionBounds)
            selectionBounds.offset(view.left.toFloat(),view.top.toFloat())
        }
        val x=selectionBounds.centerX();val y=selectionBounds.centerY()
        val halfWidth=selectionBounds.width()/2f;val halfHeight=selectionBounds.height()/2f
        if(material.lensX==x&&material.lensY==y&&material.lensHalfWidth==halfWidth&&material.lensHalfHeight==halfHeight)return
        val next=material.copy(
            lensX=x,lensY=y,lensHalfWidth=halfWidth,lensHalfHeight=halfHeight)
        if(next!=material){material=next;invalidate()}
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean{
        val down=event.pointerCount==1&&(event.actionMasked==MotionEvent.ACTION_DOWN||event.actionMasked==MotionEvent.ACTION_MOVE)
        if(material.style==LiquidGlassStyle.DEFAULT){
            val next=material.copy(touchX=event.x,touchY=event.y,pressed=down)
            if(next!=material){material=next;invalidate()}
        }
        return super.dispatchTouchEvent(event)
    }
    override fun draw(canvas:Canvas){
        val source=backdrop
        if(source!=null&&!isIndependentSource(source)){
            // Never fall back to BlurView here: its ancestor capture would recurse too.
            isLiquidGlassRendering=false;liquidGlassFailure="Glass source contains the glass view"
            background?.let{it.setBounds(0,0,width,height);it.draw(canvas)}
            dispatchDraw(canvas);onDrawForeground(canvas);return
        }
        if(Build.VERSION.SDK_INT>=33&&canvas.isHardwareAccelerated&&source!=null&&material.opacity>0f&&width>0&&height>0){
            // Visibility/layout can change without another material update (selection action mode).
            updateSelectionLens()
            val lens=renderer?:LiquidLensRenderer(resources.displayMetrics.density).also{renderer=it}
            if(lens.draw(canvas,this,source,material)){
                isLiquidGlassRendering=true;liquidGlassFailure=null
                // Do not call BlurView.draw or apply RenderEffect to this View: that would blur/refract text.
                dispatchDraw(canvas)
                if(material.style==LiquidGlassStyle.DEFAULT)onDrawForeground(canvas)
                return
            }
            liquidGlassFailure=lens.failure
        }
        isLiquidGlassRendering=false
        if (inheritsBackdrop) {
            // Source-over compatibility material on the already composited header.
            background?.let { it.setBounds(0, 0, width, height); it.draw(canvas) }
            dispatchDraw(canvas)
            onDrawForeground(canvas)
            return
        }
        if((selectionOverflowEnabled||material.style!=LiquidGlassStyle.DEFAULT)&&width>0&&height>0){
            // Legacy/software paths still need a rounded body after disabling outline clipping.
            // Paint only the selector's exterior a second time; labels are drawn exactly once.
            compatibilityClip.reset()
            val radius=minOf(width,height)/2f
            compatibilityClip.addRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,android.graphics.Path.Direction.CW)
            val body=canvas.save()
            canvas.clipPath(compatibilityClip)
            super.draw(canvas)
            canvas.restoreToCount(body)
            selectionLens?.get()?.takeIf{it.parent===this&&it.visibility==View.VISIBLE}?.let{view->
                val exterior=canvas.save()
                @Suppress("DEPRECATION")
                canvas.clipPath(compatibilityClip,android.graphics.Region.Op.DIFFERENCE)
                canvas.translate(view.left.toFloat(),view.top.toFloat());canvas.concat(view.matrix)
                view.draw(canvas)
                canvas.restoreToCount(exterior)
            }
        }else super.draw(canvas)
    }
    override fun onAttachedToWindow(){
        super.onAttachedToWindow();viewTreeObserver.addOnPreDrawListener(positionListener)
    }
    override fun onDetachedFromWindow(){
        if(viewTreeObserver.isAlive)viewTreeObserver.removeOnPreDrawListener(positionListener)
        if(Build.VERSION.SDK_INT>=33)renderer?.release()
        renderer=null;isLiquidGlassRendering=false;super.onDetachedFromWindow()
    }
}

/** Values describe background optics only; never owns a View's geometry or foreground transforms. */
internal data class LiquidGlassOptics(
    val strength:Float=0f,val dark:Boolean=false,val opacity:Float=1f,
    val lensX:Float=0f,val lensY:Float=0f,val lensHalfWidth:Float=0f,val lensHalfHeight:Float=0f,
    val selectionOverflow:Boolean=false,
    val touchX:Float=0f,val touchY:Float=0f,val pressed:Boolean=false,
    val style:LiquidGlassStyle=LiquidGlassStyle.DEFAULT
) {
    val bevelDp: Float get() = 13f + 2f * strength
    val refractionDp: Float get() = 11f + 8f * strength
    val selectionRefractionDp: Float get() = 3f + 7f * strength
}

internal enum class LiquidGlassStyle { DEFAULT, HAZE_DOCK }
