package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/** Taps remain child accessibility actions; a held/dragged/multi-touch sequence never becomes a tap. */
class GestureDock(context:Context):LiquidGlassView(context){
    var dragEnabled=true
        set(value){if(field!=value){field=value;if(!value)cancelGesture()}}
    var selectedIndex=0
    var onDrag:(Float)->Unit={}
    var onPressChanged:(Boolean)->Unit={}
    var onRelease:(Int,Boolean)->Unit={_,_->}
    private var startX=0f
    private var startY=0f
    private var lastX=0f
    private var lastY=0f
    private var startPosition=0f
    private var position=0f
    private var tracking=false
    private var dragging=false
    private var holding=false
    private var blocked=false
    private var childCancelled=false
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val longPress=Runnable{
        if(tracking&&!dragging&&!blocked&&dragEnabled&&isAttachedToWindow){
            holding=true
            cancelChildTouch()
            parent?.requestDisallowInterceptTouchEvent(true)
            onPressChanged(true)
        }
    }

    override fun dispatchTouchEvent(event:MotionEvent):Boolean{
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{
                cancelGesture()
                tracking=dragEnabled&&event.pointerCount==1
                blocked=false;childCancelled=false
                startX=event.x;startY=event.y;lastX=event.x;lastY=event.y
                startPosition=selectedIndex.toFloat();position=startPosition
                if(tracking)postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP->{
                if(tracking||dragging||holding||blocked){
                    cancelChildTouch();cancelGesture();blocked=true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE->{
                lastX=event.x;lastY=event.y
                if(blocked)return true
                if(tracking){
                    val dx=event.x-startX;val dy=event.y-startY
                    if(!dragging&&abs(dx)>slop&&abs(dx)>abs(dy)*1.3f){
                        removeCallbacks(longPress);dragging=true;cancelChildTouch()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        if(!holding)onPressChanged(true)
                    }else if(!dragging&&(abs(dx)>slop||abs(dy)>slop)){
                        cancelChildTouch();cancelGesture();blocked=true
                        return true
                    }
                    if(dragging){updatePosition(event.x);return true}
                    if(holding)return true
                }
            }
            MotionEvent.ACTION_UP->{
                removeCallbacks(longPress)
                if(blocked||holding||dragging){
                    val choose=if(dragging&&!blocked){if(position>=.5f)1 else 0}else selectedIndex
                    val cancelled=!dragging||blocked
                    resetTracking();blocked=false
                    onPressChanged(false);onRelease(choose,cancelled)
                    return true
                }
                resetTracking()
            }
            MotionEvent.ACTION_CANCEL->{
                val consumed=blocked||holding||dragging
                cancelGesture();blocked=false
                if(consumed)return true
            }
        }
        val handled=super.dispatchTouchEvent(event)
        // Own an otherwise unhandled DOWN while the timer is active, so UP/CANCEL returns here.
        return handled||tracking
    }

    /** Also used by page/mode changes: cancel the old child, timer and optics as one operation. */
    fun cancelGesture(){
        val active=tracking||holding||dragging
        if(active)cancelChildTouch()
        val optical=holding||dragging
        resetTracking()
        if(active){blocked=true;if(optical)onPressChanged(false);onRelease(selectedIndex,true)}
    }
    private fun resetTracking(){
        removeCallbacks(longPress);tracking=false;holding=false;dragging=false
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    private fun cancelChildTouch(){
        if(childCancelled)return
        childCancelled=true
        val now=SystemClock.uptimeMillis()
        val cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,lastX,lastY,0)
        try{super.dispatchTouchEvent(cancel)}finally{cancel.recycle()}
    }
    private fun updatePosition(x:Float){
        position=(startPosition+(x-startX)/(width/2f).coerceAtLeast(1f)).coerceIn(0f,1f)
        onDrag(position)
    }
    override fun onDetachedFromWindow(){cancelGesture();super.onDetachedFromWindow()}
}
