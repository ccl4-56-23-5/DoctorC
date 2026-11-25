/* Floating glass surfaces, GPL-3.0-or-later. No icon geometry is drawn here. */
package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import eightbitlab.com.blurview.BlurView
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Paints independent floating surfaces. Layout, tab state and all transforms remain the
 * caller's responsibility: this class never writes width, height, translation or scale.
 *
 * LiquidGlassView adds GPU lens optics on API 33+. The same BlurTarget feeds BlurView's
 * legacy compatibility path, with one overlay tint and a fine rim. Foreground labels remain clear.
 */
class GlassMaterial(context:Context) {
    private val dark=Palette(context).dark
    private val density=context.resources.displayMetrics.density
    private val edgeWidth=(density*.65f).roundToInt().coerceAtLeast(1)
    private val surfaces=WeakHashMap<BlurView,SurfaceState>()
    private val selectors=WeakHashMap<View,SelectorState>()
    private val toolbarSurfaces=WeakHashMap<LiquidGlassView,ToolbarSurfaceState>()

    data class Profile(
        val blurRadius:Float,
        val overlayColor:Int,
        val rimColor:Int,
        val elevationDp:Float,
        val outlineAlpha:Float
    )

    // Quantisation prevents duplicate blur reconfiguration and drawable churn from
    // scroll/layout callbacks; the gesture still has 61 material levels.
    private val profiles=Array(2){compact->Array(61){step->
        val t=step/60f
        Profile(
            // Legacy compatibility blur stays fixed; API 33+ strength belongs to lens optics.
            blurRadius=8f,
            overlayColor=if(dark)Color.argb((178-16*t).roundToInt(),33,37,46)
                else Color.argb((162-16*t).roundToInt(),250,252,255),
            rimColor=if(dark)Color.argb((98+58*t).roundToInt(),192,208,235)
                else Color.argb((192+40*t).roundToInt(),255,255,255),
            elevationDp=(if(compact==1)1.5f else 2f)+t,
            outlineAlpha=if(dark).34f else .23f
        )
    }}

    /** Public for source-level invariants and callers that need to coordinate material. */
    fun profile(strength:Float,compact:Boolean=false):Profile=profiles[if(compact)1 else 0][step(strength)]

    private val commentCompatibility = Profile(
        blurRadius=8f,
        overlayColor=if(dark)Color.argb(71,0,0,0) else Color.argb(153,255,255,255),
        rimColor=Color.argb(if(dark)56 else 97,255,255,255),
        elevationDp=2f,
        outlineAlpha=if(dark).18f else .25f
    )

    internal fun applySurface(view:BlurView,strength:Float=0f,style:LiquidGlassStyle=LiquidGlassStyle.DEFAULT) {
        val material=if(style==LiquidGlassStyle.DEFAULT)profile(strength) else commentCompatibility
        if(view is LiquidGlassView)view.setGlassMaterial(strength,dark,style)
        val optical=view is LiquidGlassView&&Build.VERSION.SDK_INT>=33
        val opticalShadow=optical&&style!=LiquidGlassStyle.DEFAULT
        val state=surfaces.getOrPut(view){
            val rim=GradientDrawable().apply{setColor(Color.TRANSPARENT)}
            SurfaceState(rim).also{
                view.background=ColorDrawable(Color.TRANSPARENT)
                view.foreground=rim
                view.clipToOutline=!opticalShadow&&!(view is LiquidGlassView&&view.selectionOverflowEnabled)
                view.outlineProvider=object:ViewOutlineProvider(){
                    override fun getOutline(v:View,outline:Outline){
                        outline.setRoundRect(0,0,v.width,v.height,minOf(v.width,v.height)/2f)
                        // A transparent background otherwise contributes no elevation shadow.
                        outline.alpha=material.outlineAlpha
                    }
                }
                if(Build.VERSION.SDK_INT>=28){
                    view.outlineAmbientShadowColor=if(dark)0x40000000 else 0x260c1830
                    view.outlineSpotShadowColor=if(dark)0x50000000 else 0x330c1830
                }
                view.setBlurEnabled(true)
            }
        }
        val corner=if(view.width>0&&view.height>0)minOf(view.width,view.height)/2f else 34f*density
        if(state.corner!=corner){state.corner=corner;state.rim.cornerRadius=corner;view.invalidateOutline()}
        // The shader draws its own rim. Keep the compatibility rim configured if the GPU path fails.
        val rimColor=if(optical&&!opticalShadow)Color.TRANSPARENT else material.rimColor
        if(state.rimColor!=rimColor){
            state.rimColor=rimColor;state.rim.setStroke(edgeWidth,rimColor)
        }
        if(state.overlayColor!=material.overlayColor){state.overlayColor=material.overlayColor;view.setOverlayColor(material.overlayColor)}
        if(abs(state.blur-material.blurRadius)>.01f){state.blur=material.blurRadius;view.setBlurRadius(material.blurRadius)}
        val elevation=if(opticalShadow)0f else material.elevationDp*density
        if(view.elevation!=elevation)view.elevation=elevation
    }

    /** Source-over fallback for the separately sampled, already blurred toolbar backdrop. */
    fun applyToolbarSurface(view:LiquidGlassView,progress:Float){
        val level=step(progress);val opacity=level/60f
        view.setInheritedBackdrop(opacity)
        view.setGlassMaterial(0f, dark)
        val state=toolbarSurfaces.getOrPut(view){
            val fill=GradientDrawable().apply{orientation=GradientDrawable.Orientation.TL_BR}
            ToolbarSurfaceState(fill).also{
                view.background=fill;view.foreground=null;view.clipToOutline=true
                // Inherited-backdrop drawing bypasses View.drawBackground: its drawable bounds
                // can still be empty when Android first asks for the clipping outline.
                view.outlineProvider=object:ViewOutlineProvider(){
                    override fun getOutline(v:View,outline:Outline){
                        outline.setRoundRect(0,0,v.width,v.height,minOf(v.width,v.height)/2f)
                        outline.alpha=1f
                    }
                }
                view.elevation=0f;view.translationZ=0f
            }
        }
        val radius=if(view.width>0&&view.height>0)minOf(view.width,view.height)/2f else 24f*density
        if(state.corner!=radius){state.corner=radius;state.fill.cornerRadius=radius;view.invalidateOutline()}
        if(state.width!=view.width||state.height!=view.height){
            state.width=view.width;state.height=view.height;view.invalidateOutline()
        }
        if(state.level!=level){
            state.level=level
            state.fill.colors=if(dark)intArrayOf(Color.argb((158*opacity).roundToInt(),54,62,76),Color.argb((94*opacity).roundToInt(),24,30,43))
                else intArrayOf(Color.argb((178*opacity).roundToInt(),255,255,255),Color.argb((116*opacity).roundToInt(),218,226,240))
            state.fill.setStroke(edgeWidth,if(dark)Color.argb((106*opacity).roundToInt(),223,233,248)
                else Color.argb((68*opacity).roundToInt(),126,145,168))
        }
    }

    /** A light lens under the labels, without a second blur or an opaque white plate. */
    fun applySelector(view:View,strength:Float=0f) {
        val level=step(strength)
        val state=selectors.getOrPut(view){
            val fill=GradientDrawable().apply{orientation=GradientDrawable.Orientation.TL_BR}
            SelectorState(fill).also{view.background=fill}
        }
        val corner=if(view.width>0&&view.height>0)minOf(view.width,view.height)/2f else 29f*density
        if(state.corner!=corner){state.corner=corner;state.fill.cornerRadius=corner}
        if(state.level!=level){
            state.level=level;val t=level/60f
            state.colors[0]=if(dark)Color.argb((34+16*t).roundToInt(),182,195,215)
                else Color.argb((32+8*t).roundToInt(),116,128,146)
            state.colors[1]=if(dark)Color.argb((24+12*t).roundToInt(),118,137,166)
                else Color.argb((24+8*t).roundToInt(),137,148,167)
            state.fill.colors=state.colors
            state.fill.setStroke(edgeWidth,if(dark)Color.argb((38+30*t).roundToInt(),216,226,244)
                else Color.argb((78+30*t).roundToInt(),255,255,255))
            // Child draw order already puts the lens under the labels. A separate shadow
            // makes the two nested glass edges look like stacked solid controls.
            view.elevation=0f
        }
        (view.parent as? LiquidGlassView)?.setSelectionLens(view)
    }

    private fun step(value:Float):Int=((if(value.isFinite())value else 0f).coerceIn(0f,1f)*60).roundToInt()
    private class SurfaceState(val rim:GradientDrawable){
        var corner=-1f;var rimColor=Int.MIN_VALUE;var overlayColor=Int.MIN_VALUE;var blur=-1f
    }
    private class ToolbarSurfaceState(val fill:GradientDrawable){var corner=-1f;var width=-1;var height=-1;var level=-1}
    private class SelectorState(val fill:GradientDrawable){
        var corner=-1f;var level=-1;val colors=IntArray(2)
    }
}
