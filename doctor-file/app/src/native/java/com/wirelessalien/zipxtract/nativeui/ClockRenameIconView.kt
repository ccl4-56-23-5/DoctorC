package com.wirelessalien.zipxtract.nativeui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatImageView

/** A minute clock, not an animation. The Activity must forward its resumed state explicitly. */
class ClockRenameIconView @JvmOverloads constructor(
    context:Context,
    attrs:AttributeSet?=null,
    defStyleAttr:Int=0,
    private val timeSource:ClockRenameDrawable.TimeSource=ClockRenameDrawable.SYSTEM_TIME
):AppCompatImageView(context,attrs,defStyleAttr){
    private var schedulingReady=false
    private val handler=Handler(Looper.getMainLooper())
    private var hostResumed=false
    private var aggregatedVisible=true
    private var receiverRegistered=false
    private var scheduled=false
    private var tickDelay=0L
    val isMinuteTickScheduled:Boolean get()=scheduled
    val nextTickDelayMillis:Long get()=tickDelay
    val isClockReceiverRegistered:Boolean get()=receiverRegistered
    private val clockDrawable=ClockRenameDrawable(Palette(context).dark,timeSource)
    private val minuteTick=object:Runnable{
        override fun run(){
            scheduled=false;tickDelay=0L
            if(canRun()){
                clockDrawable.refreshTime()
                scheduleNextTick()
            }else updateSchedule()
        }
    }
    private val clockChanges=object:BroadcastReceiver(){
        override fun onReceive(context:Context?,intent:Intent?){
            if(intent?.action==Intent.ACTION_TIME_CHANGED||intent?.action==Intent.ACTION_TIMEZONE_CHANGED){
                if(canRun()){
                    cancelTick();clockDrawable.refreshTime();scheduleNextTick()
                }else updateSchedule()
            }
        }
    }

    init{
        scaleType=ScaleType.FIT_CENTER
        setImageDrawable(clockDrawable)
        schedulingReady=true
    }
    fun setHostResumed(resumed:Boolean){
        hostResumed=resumed
        updateSchedule()
    }
    private fun canRun():Boolean=hostResumed&&isAttachedToWindow&&isShown&&aggregatedVisible&&windowVisibility==View.VISIBLE
    private fun updateSchedule(){
        if(!schedulingReady)return
        if(canRun()){
            if(!receiverRegistered){
                val filter=IntentFilter().apply{addAction(Intent.ACTION_TIME_CHANGED);addAction(Intent.ACTION_TIMEZONE_CHANGED)}
                if(Build.VERSION.SDK_INT>=33)context.registerReceiver(clockChanges,filter,Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("DEPRECATION") context.registerReceiver(clockChanges,filter)
                receiverRegistered=true
            }
            clockDrawable.refreshTime()
            if(!scheduled)scheduleNextTick()
        }else{
            cancelTick()
            if(receiverRegistered){context.unregisterReceiver(clockChanges);receiverRegistered=false}
        }
    }
    private fun scheduleNextTick(){
        if(scheduled||!canRun())return
        val remainder=((timeSource.nowMillis()%60_000L)+60_000L)%60_000L
        tickDelay=60_000L-remainder
        scheduled=true
        handler.postDelayed(minuteTick,tickDelay)
    }
    private fun cancelTick(){handler.removeCallbacks(minuteTick);scheduled=false;tickDelay=0L}
    override fun onAttachedToWindow(){super.onAttachedToWindow();updateSchedule()}
    override fun onDetachedFromWindow(){
        cancelTick()
        if(receiverRegistered){context.unregisterReceiver(clockChanges);receiverRegistered=false}
        super.onDetachedFromWindow()
    }
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);if(schedulingReady)updateSchedule()}
    override fun onVisibilityAggregated(isVisible:Boolean){
        super.onVisibilityAggregated(isVisible);aggregatedVisible=isVisible
        if(schedulingReady)updateSchedule()
    }
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);if(schedulingReady)updateSchedule()}
}
