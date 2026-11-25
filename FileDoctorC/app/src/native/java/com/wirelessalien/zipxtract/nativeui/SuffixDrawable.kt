package com.wirelessalien.zipxtract.nativeui
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
class SuffixDrawable(private val context:Context,private val text:String):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Palette(context).secondary;textSize=13*context.resources.displayMetrics.scaledDensity;textAlign=Paint.Align.CENTER}
    override fun getIntrinsicWidth()=context.dp(38)
    override fun getIntrinsicHeight()=context.dp(24)
    override fun draw(canvas:Canvas){canvas.drawText(text,bounds.exactCenterX(),bounds.exactCenterY()-(paint.ascent()+paint.descent())/2,paint)}
    override fun setAlpha(value:Int){paint.alpha=value}
    override fun setColorFilter(value:ColorFilter?){paint.colorFilter=value}
    @Deprecated("Deprecated in Java")override fun getOpacity()=PixelFormat.TRANSLUCENT
}
