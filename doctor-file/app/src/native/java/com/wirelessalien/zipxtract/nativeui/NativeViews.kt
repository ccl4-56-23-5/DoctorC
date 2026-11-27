/* Original native UI, 2026-11-03. GPL-3.0-or-later. No Material widgets. */
package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.*
import android.widget.*
import android.text.TextUtils
import android.content.res.Configuration

fun Context.dp(value:Float):Int=(resources.displayMetrics.density*value+.5f).toInt()
fun Context.dp(value:Int):Int=dp(value.toFloat())
class Palette(context:Context){
    val dark=(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES
    val page=if(dark)Color.rgb(20,21,24)else Color.WHITE
    val surface=if(dark)0xff222632.toInt() else 0xfff2f5fa.toInt()
    val text=if(dark)Color.rgb(245,245,247)else Color.rgb(22,23,27)
    val secondary=if(dark)0xffaeb8c9.toInt() else 0xff59677c.toInt()
    val blue=if(dark)0xff9bbaff.toInt() else 0xff245bd6.toInt()
    val onAccent=if(dark)0xff112249.toInt() else Color.WHITE
    val selected=if(dark)0xff253856.toInt() else 0xffe8efff.toInt()
    val line=if(dark)0xff394352.toInt() else 0xffe0e7f1.toInt()
    fun background(color:Int,radius:Float):GradientDrawable=GradientDrawable().apply{setColor(color);cornerRadius=radius}
    fun pressable(color: Int, radius: Float) = RippleDrawable(
        ColorStateList.valueOf(if (dark) 0x309bbaff else 0x24245bd6),
        background(color, radius), background(Color.WHITE, radius)
    )
}
fun Context.label(value:String,size:Float=16f,color:Int=Palette(this).text,bold:Boolean=false):TextView=TextView(this).apply {
    text=value;textSize=size;setTextColor(color);includeFontPadding=false
    typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
    gravity=Gravity.CENTER_VERTICAL
}
fun View.accessible(value:String){contentDescription=value;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES}

fun Context.iconButton(name:String,description:String,color:Int=Palette(this).text,click:()->Unit):ImageButton=ImageButton(this).apply {
    scaleType=ImageView.ScaleType.FIT_CENTER
    setImageDrawable(Art.drawable(context,name));setPadding(dp(9),dp(9),dp(9),dp(9))
    background = Palette(context).pressable(Color.TRANSPARENT, dp(24).toFloat())
    accessible(description);minimumWidth=dp(48);minimumHeight=dp(48);if(color==Palette(context).secondary)imageAlpha=170;setOnClickListener{menuAnchor();click()}
}
class NativeToggle(context:Context):CompoundButton(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{buttonDrawable=null;isClickable=true;isFocusable=true;minWidth=context.dp(60);minHeight=context.dp(48);setPadding(0,0,0,0)}
    override fun onDraw(c:Canvas){
        val d=resources.displayMetrics.density;val w=48*d;val h=29*d;val x=(width-w)/2;val y=(height-h)/2
        p.color=if(isChecked)Palette(context).blue else if(Palette(context).dark)0xff555963.toInt() else 0xffdfe1e7.toInt()
        c.drawRoundRect(x,y,x+w,y+h,h/2,h/2,p);p.color=if(isChecked)Palette(context).onAccent else Color.WHITE
        c.drawCircle(x+(if(isChecked)w-h/2 else h/2),y+h/2,11.5f*d,p)
    }
    override fun setChecked(checked:Boolean){super.setChecked(checked);invalidate()}
    override fun getAccessibilityClassName():CharSequence="android.widget.Switch"
}
/** A full-bleed alpha mask; no rounded header box, boundary stroke or shadow edge. */
class FadeMask(context:Context):FrameLayout(context){
    private val mask=Paint(Paint.ANTI_ALIAS_FLAG).apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        // Smoothstep gives both ends a zero slope, avoiding a visible band at the edge.
        val stops=FloatArray(25){it/24f}
        val colors=IntArray(stops.size){i->
            val t=((stops[i]-.35f)/.65f).coerceIn(0f,1f)
            val opacity=1f-t*t*(3f-2f*t)
            Color.argb((255*opacity+.5f).toInt(),255,255,255)
        }
        mask.shader=LinearGradient(0f,0f,0f,h.coerceAtLeast(1).toFloat(),colors,stops,Shader.TileMode.CLAMP)
    }
    override fun dispatchDraw(c:Canvas){
        val layer=c.saveLayer(0f,0f,width.toFloat(),height.toFloat(),null)
        super.dispatchDraw(c)
        c.drawRect(0f,0f,width.toFloat(),height.toFloat(),mask);c.restoreToCount(layer)
    }
}
