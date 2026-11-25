package com.wirelessalien.zipxtract.nativeui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Static graphite rename silhouette; only a tiny white glint follows the local hour hand. */
class ClockRenameDrawable(
    private val dark:Boolean=false,
    private val timeSource:TimeSource=SYSTEM_TIME
):Drawable(){
    fun interface TimeSource{fun nowMillis():Long}
    companion object{
        val SYSTEM_TIME=TimeSource{System.currentTimeMillis()}
        /** Clockwise from twelve; minutes move the hour hand rather than the minute hand. */
        fun hourHandAngleDegrees(hour:Int,minute:Int):Float=((hour%12)+minute/60f)*30f
    }

    private val body=Path().apply{
        moveTo(31f,17f);lineTo(59f,17f);lineTo(76f,34f);lineTo(76f,78f)
        quadTo(76f,84f,70f,84f);lineTo(31f,84f);quadTo(25f,84f,25f,78f)
        lineTo(25f,23f);quadTo(25f,17f,31f,17f);close()
    }
    private val fold=Path().apply{moveTo(59f,17f);lineTo(59f,29f);quadTo(59f,34f,64f,34f);lineTo(76f,34f);close()}
    private val bodyPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val detailPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND;strokeWidth=3.4f}
    private val haloPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        shader=RadialGradient(0f,0f,7f,intArrayOf(Color.argb(225,255,255,255),Color.argb(110,255,255,255),Color.TRANSPARENT),floatArrayOf(0f,.35f,1f),Shader.TileMode.CLAMP)
    }
    private var drawableAlpha=255
    private var bodyFilter:ColorFilter?=null
    private var lastMinute=Long.MIN_VALUE
    private var lastZoneOffset=Int.MIN_VALUE
    private var angle=0f
    /** Snapshot updated by refreshTime/draw, useful for deterministic clock/pixel regression tests. */
    val currentHourHandAngleDegrees:Float get()=angle

    init{updateTime()}

    private fun updateTime():Boolean{
        val now=timeSource.nowMillis()
        val local=Calendar.getInstance().apply{timeInMillis=now}
        val offset=local.get(Calendar.ZONE_OFFSET)+local.get(Calendar.DST_OFFSET)
        val minute=now/60_000L
        if(minute==lastMinute&&offset==lastZoneOffset)return false
        lastMinute=minute;lastZoneOffset=offset
        val next=hourHandAngleDegrees(local.get(Calendar.HOUR_OF_DAY),local.get(Calendar.MINUTE))
        if(next==angle)return false
        angle=next;return true
    }
    /** The owning IconView calls this at most once per minute, or when becoming visible again. */
    fun refreshTime(){if(updateTime())invalidateSelf()}

    override fun draw(canvas:Canvas){
        if(drawableAlpha==0||bounds.isEmpty)return
        // A generic Art.drawable caller also gets the right local time on its next normal draw.
        // There is deliberately no invalidateSelf/scheduleSelf or Handler operation in draw.
        updateTime()
        val size=min(bounds.width(),bounds.height()).toFloat()
        val save=canvas.save()
        canvas.translate(bounds.exactCenterX()-size/2f,bounds.exactCenterY()-size/2f)
        canvas.scale(size/100f,size/100f)
        bodyPaint.shader=null;bodyPaint.style=Paint.Style.FILL;bodyPaint.color=if(dark)0xffdddddf.toInt() else 0xff25262c.toInt()
        bodyPaint.alpha=drawableAlpha;bodyPaint.colorFilter=bodyFilter
        canvas.drawPath(body,bodyPaint)
        bodyPaint.color=if(dark)0xffc3c4c8.toInt() else 0xff414248.toInt();bodyPaint.alpha=drawableAlpha
        canvas.drawPath(fold,bodyPaint)
        // Fixed rename insertion cursor: the subject never rotates with the clock.
        detailPaint.color=if(dark)0xff25262c.toInt() else 0xffeeeeef.toInt()
        detailPaint.alpha=drawableAlpha;detailPaint.colorFilter=bodyFilter
        canvas.drawLine(50f,42f,50f,65f,detailPaint)
        canvas.drawLine(44f,42f,56f,42f,detailPaint)
        canvas.drawLine(44f,65f,56f,65f,detailPaint)
        val radians=Math.toRadians((angle-90f).toDouble())
        val glow=canvas.save()
        canvas.translate(50f+26f*cos(radians).toFloat(),50f+26f*sin(radians).toFloat())
        // No overall color matrix: the halo stays white in both themes and honors drawable alpha.
        haloPaint.alpha=drawableAlpha;haloPaint.colorFilter=null
        canvas.drawCircle(0f,0f,7f,haloPaint)
        canvas.restoreToCount(glow)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int){val next=alpha.coerceIn(0,255);if(drawableAlpha!=next){drawableAlpha=next;invalidateSelf()}}
    override fun getAlpha():Int=drawableAlpha
    override fun setColorFilter(colorFilter:ColorFilter?){if(bodyFilter!==colorFilter){bodyFilter=colorFilter;invalidateSelf()}}
    @Suppress("DEPRECATION") override fun getOpacity():Int=PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth():Int=192
    override fun getIntrinsicHeight():Int=192
}
