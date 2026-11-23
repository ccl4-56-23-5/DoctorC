package com.wirelessalien.zipxtract.nativeui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.wirelessalien.zipxtract.R
import java.util.WeakHashMap

/** Motion changes compositor position only. Text never scales or crossfades over other text. */
object UiMotion {
    val ease=PathInterpolator(.2f,.75f,.2f,1f)
    private val leave=PathInterpolator(.3f,0f,.7f,1f)
    const val ENTER=280L
    const val EXIT=220L
    private class Run(val animator:ValueAnimator,var cancelled:Boolean=false)
    private val active=WeakHashMap<View,Run>()
    fun cancel(v:View){active.remove(v)?.let{it.cancelled=true;it.animator.cancel()};v.animate().cancel()}
    fun drive(v:View,from:Float,to:Float,duration:Long,frame:(Float)->Unit,done:()->Unit={}){
        cancel(v)
        val animator=ValueAnimator.ofFloat(from,to);val run=Run(animator);active[v]=run
        animator.duration=(duration*kotlin.math.abs(to-from)).toLong().coerceAtLeast(1)
        animator.interpolator=if(to<from)leave else ease
        animator.addUpdateListener{if(active[v]===run)frame(it.animatedValue as Float)}
        animator.addListener(object:AnimatorListenerAdapter(){
            override fun onAnimationCancel(animation:Animator){run.cancelled=true}
            override fun onAnimationEnd(animation:Animator){if(active[v]!==run)return;active.remove(v);if(!run.cancelled)done()}
        })
        frame(from);animator.start()
    }
    // Directory-picker lists keep their full opacity and slide as a single, clipped opaque page.
    fun enter(v:View,page:Boolean=false){
        v.alpha=1f;v.scaleX=1f;v.scaleY=1f
        val distance=(v.width.takeIf{it>0}?:v.resources.displayMetrics.widthPixels)
        drive(v,0f,1f,ENTER,{v.translationX=MotionFrames.slide(distance,it).toFloat();v.translationY=0f})
    }
}

/** Read the same rounded geometry that Android uses to draw the stationary control. */
internal fun View.surfaceCornerRadius(width: Int, height: Int, fallback: Float): Float {
    val outline = Outline()
    outlineProvider?.getOutline(this, outline)
    val radius = if (outline.getRect(Rect())) outline.radius
        else (background as? GradientDrawable)?.cornerRadius ?: fallback
    return radius.coerceIn(0f, minOf(width, height) / 2f)
}

/** Full-screen transparent window; modal placement, local dim and fixed surface are View properties. */
class MotionDialog(context:Context,theme:Int,private val page:Boolean=false):Dialog(context,theme){
    lateinit var motionContent:View
    private val activity = context as? Activity
    /** Optional source control: expand a rounded clipping outline, without scaling any text. */
    var morphFrom:View?=null
    private var morphOrigin:android.graphics.Rect?=null
    private val morphClip=android.graphics.Rect()
    private val hostLocation=IntArray(2)
    private var morphRadius=0f
    private var sourceRadius = 0f
    private var targetRadius = 0f
    var onBackAction:()->Boolean={false}
    var onMotionFrame: ((Float) -> Unit)? = null
    var onEntered: (() -> Unit)? = null
    private var closing=false
    private var disposed=false
    private var finished=false
    private var visibility=0f
    private var distance=1
    private var host:FrameLayout?=null
    private var dim:View?=null
    private var dimLevel=.2f
    private var bottom=false
    private var topAligned = false
    private var gap=0
    private var baseTop=0
    private var viewportHeight=0
    private var systemTop=0
    private var systemBottom=0
    private var finishAction:(()->Unit)?=null
    val layoutFrozen:Boolean get()=closing||finished||disposed

    fun showPanel(surface: View, width: Int, bottom: Boolean = false, dimAmount: Float = .2f,
                  topAligned: Boolean = false) {
        check(!page);check(!isShowing);require(!bottom || !topAligned)
        this.bottom=bottom;this.topAligned=topAligned;dimLevel=dimAmount;gap=context.dp(12);motionContent=surface
        val owner = if (topAligned) activity else context as? Activity
        val decor = owner?.window?.decorView
        val ownerInsets = decor?.let(ViewCompat::getRootWindowInsets)
        val bars = ownerInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
        systemTop = bars?.top ?: 0
        systemBottom = bars?.bottom ?: 0
        val topTypes = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
        if (topAligned && decor != null) {
            val visibleFrame = Rect().also(decor::getWindowVisibleDisplayFrame)
            systemTop = maxOf(systemTop, visibleFrame.top,
                ownerInsets?.getInsetsIgnoringVisibility(topTypes)?.top ?: 0)
        }
        viewportHeight=context.resources.displayMetrics.heightPixels
        val safeWidth=(context.resources.displayMetrics.widthPixels-context.dp(24)).coerceAtLeast(1)
        val frozenWidth=minOf(width,safeWidth).coerceAtLeast(1)
        val safeHeight=(viewportHeight-systemTop-systemBottom-2*gap).coerceAtLeast(1)
        surface.measure(View.MeasureSpec.makeMeasureSpec(frozenWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(safeHeight,View.MeasureSpec.AT_MOST))
        val frozenHeight=surface.measuredHeight.coerceIn(1,safeHeight)
        baseTop = when {
            topAligned -> systemTop + gap
            bottom -> viewportHeight - systemBottom - gap - frozenHeight
            else -> systemTop + (safeHeight + 2 * gap - frozenHeight) / 2
        }
        distance=(viewportHeight-baseTop+gap).coerceAtLeast(1)
        surface.isClickable=true
        val root=FrameLayout(context).apply{id=R.id.native_modal_host;clipChildren=true;setBackgroundColor(Color.TRANSPARENT)}
        dim=View(context).apply{id=R.id.native_modal_dim;setBackgroundColor(Color.BLACK);alpha=0f;setOnClickListener{cancel()}}
        root.addView(dim,FrameLayout.LayoutParams(-1,-1))
        root.addView(surface,FrameLayout.LayoutParams(frozenWidth,frozenHeight,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=baseTop})
        host=root;super.setContentView(root)
        morphFrom?.takeIf{it.isAttachedToWindow&&it.width>0&&it.height>0}?.let{source->
            val location=IntArray(2);source.getLocationOnScreen(location)
            morphOrigin=android.graphics.Rect(location[0],location[1],location[0]+source.width,location[1]+source.height)
            sourceRadius = source.surfaceCornerRadius(source.width, source.height, minOf(source.width, source.height) / 2f)
            targetRadius = surface.surfaceCornerRadius(frozenWidth, frozenHeight, 0f)
            surface.clipToOutline=true
            surface.outlineProvider=object:android.view.ViewOutlineProvider(){
                override fun getOutline(v:View,o:android.graphics.Outline){o.setRoundRect(morphClip,morphRadius)}
            }
            surface.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->if(!layoutFrozen)frame(visibility)}
        }
        ViewCompat.setOnApplyWindowInsetsListener(root){_,insets->
            if(!closing){
                val ime=insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val h=root.height.takeIf{it>0}?:viewportHeight
                val availableBottom=h-maxOf(systemBottom,ime)-gap
                val top = when {
                    topAligned -> maxOf(systemTop, insets.getInsetsIgnoringVisibility(topTypes).top) + gap
                    bottom -> availableBottom - frozenHeight
                    else -> systemTop + (availableBottom - systemTop - frozenHeight) / 2
                }
                val lp=surface.layoutParams as FrameLayout.LayoutParams
                val nextTop=top.coerceAtLeast(systemTop+gap)
                if(lp.topMargin!=nextTop){lp.topMargin=nextTop;surface.layoutParams=lp}
                distance=(h-nextTop+gap).coerceAtLeast(1)
            }
            insets
        }
        configureWindow();show()
    }
    private fun configureWindow(){window?.apply{
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setWindowAnimations(0)
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        setDimAmount(0f);setLayout(-1,-1);setGravity(Gravity.FILL)
        WindowCompat.setDecorFitsSystemWindows(this,false)
        WindowCompat.getInsetsController(this,decorView).apply{isAppearanceLightStatusBars=!Palette(context).dark;isAppearanceLightNavigationBars=!Palette(context).dark}
        statusBarColor=Color.TRANSPARENT;navigationBarColor=Color.TRANSPARENT
        // Insets are applied to content explicitly. The platform must never resize a moving window.
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }}
    override fun onStart(){
        configureWindow();super.onStart();closing=false;finished=false;visibility=0f
        motionContent.alpha=1f;motionContent.scaleX=1f;motionContent.scaleY=1f
        if(page)distance=context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        else check(host!=null){"Modal panels must be measured by showPanel before showing"}
        UiMotion.drive(motionContent,0f,1f,UiMotion.ENTER,::frame){onEntered?.invoke()}
    }
    private fun frame(value:Float){
        visibility=value
        if(page){motionContent.translationX=MotionFrames.slide(distance,value).toFloat();motionContent.translationY=0f}
        else if(morphOrigin!=null){
            val origin=morphOrigin!!;val v=motionContent
            val w=(v.width.takeIf{it>0}?:v.measuredWidth).coerceAtLeast(1)
            val h=(v.height.takeIf{it>0}?:v.measuredHeight).coerceAtLeast(1)
            val clipW=origin.width()+(w-origin.width())*value
            val clipH=origin.height()+(h-origin.height())*value
            morphClip.set(((w-clipW)/2).toInt(),((h-clipH)/2).toInt(),((w+clipW)/2).toInt(),((h+clipH)/2).toInt())
            morphRadius = sourceRadius + (targetRadius - sourceRadius) * value
            host?.getLocationOnScreen(hostLocation)
            v.translationX=(origin.exactCenterX()-hostLocation[0]-v.left-w/2f)*(1-value)
            v.translationY=(origin.exactCenterY()-hostLocation[1]-(v.layoutParams as? FrameLayout.LayoutParams)?.topMargin.orZero()-h/2f)*(1-value)
            v.invalidateOutline();dim?.alpha=dimLevel*value
        }
        else{motionContent.translationX=0f;motionContent.translationY=MotionFrames.slide(distance,value).toFloat();dim?.alpha=dimLevel*value}
        onMotionFrame?.invoke(value)
    }
    private fun Int?.orZero()=this?:0
    fun close(done:()->Unit){
        if(closing||disposed||finished)return
        if(!isShowing){finished=true;done();return}
        closing=true;finishAction=done
        // Keep the window modal, its bounds fixed and its paper opaque until fully off screen.
        UiMotion.drive(motionContent,visibility,0f,UiMotion.EXIT,::frame){finishClose()}
    }
    private fun finishClose(){
        if(finished||disposed)return
        finished=true;closing=false;val done=finishAction;finishAction=null
        super.dismiss();done?.invoke()
    }
    override fun dismiss(){close{}}
    @Deprecated("Deprecated in Java") override fun onBackPressed(){if(!closing&&!disposed&&!finished&&!onBackAction())super.onBackPressed()}
    override fun onStop(){
        if(::motionContent.isInitialized)UiMotion.cancel(motionContent)
        // If the platform removed the window, do not retain an inert Closing state.
        if(closing&&!finished&&!disposed){finished=true;closing=false;val done=finishAction;finishAction=null;done?.invoke()}
        super.onStop()
    }
    fun dispose(){
        if(disposed)return
        disposed=true;finished=true;closing=false;finishAction=null
        if(::motionContent.isInitialized)UiMotion.cancel(motionContent)
        super.dismiss()
    }
}
