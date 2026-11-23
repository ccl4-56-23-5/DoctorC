/*
 * SPDX-License-Identifier: MIT
 * Lens/SDF optical model adapted from QWEA0/Liquid-Glass-Android GlassLensRenderer.kt
 * Copyright (c) 2025-2026 pandadog. MIT license is packaged in assets/LiquidGlass-MIT.txt.
 * Source: 16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d
 * Adaptation: independent BlurTarget capture, capsule-only geometry, movable inner selection lens,
 * sharp backdrop refraction and gesture-driven optical strength; no parent capture, JNI, sensor or Bitmap path.
 */
package com.wirelessalien.zipxtract.nativeui

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import kotlin.math.ceil

@RequiresApi(33)
internal class LiquidLensRenderer(private val density:Float){
    private val node=RenderNode("Liquid glass background")
    private val backdrops=mutableMapOf<LiquidGlassStyle,RenderEffect>()
    private val shadowPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadow:RenderNode?=null
    private var shadowWidth=0
    private var shadowHeight=0
    private var shadowDark=false
    private var shader:RuntimeShader?=null
    private var lastParams:LiquidGlassOptics?=null
    private var lastWidth=0
    private var lastHeight=0
    private val ownLocation=IntArray(2)
    private val sourceLocation=IntArray(2)
    var failure:String?=null;private set

    private fun backdrop(style:LiquidGlassStyle):RenderEffect=backdrops.getOrPut(style){
        val radius=style.blurDp*density
        val gaussian=RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP)
        if(style==LiquidGlassStyle.DEFAULT)gaussian else {
            // HWUI applies the inner effect first. Match the reference's saturation before blur.
            val matrix=ColorMatrix().apply{setSaturation(1.5f)}
            val vibrancy=RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
            RenderEffect.createChainEffect(gaussian,vibrancy)
        }
    }

    private fun drawShadow(canvas:Canvas,w:Int,h:Int,dark:Boolean){
        val shadowRadius=24f*density
        val shadowOffset=(4f*density).toInt()
        val padding=ceil(3f*(shadowRadius*.57735f+.5f)).toInt()
        val layer=shadow?:RenderNode("Liquid glass shadow").also{
            it.setRenderEffect(RenderEffect.createBlurEffect(shadowRadius,shadowRadius,Shader.TileMode.CLAMP))
            shadow=it
        }
        if(shadowWidth!=w||shadowHeight!=h||shadowDark!=dark){
            layer.setPosition(-padding,shadowOffset-padding,w+padding,h+shadowOffset+padding)
            val recording=layer.beginRecording(w+2*padding,h+2*padding)
            try{
                val r=minOf(w,h)/2f
                shadowPaint.color=if(dark)0x12000000 else 0x1a000000
                recording.drawRoundRect(padding.toFloat(),padding.toFloat(),
                    (padding+w).toFloat(),(padding+h).toFloat(),r,r,shadowPaint)
            }finally{layer.endRecording()}
            shadowWidth=w;shadowHeight=h;shadowDark=dark
        }
        canvas.drawRenderNode(layer)
    }

    fun draw(canvas:Canvas,glass:View,source:View,p:LiquidGlassOptics):Boolean{
        if(failure!=null||!canvas.isHardwareAccelerated||source.width<=0||source.height<=0)return false
        val w=glass.width;val h=glass.height
        // The optical silhouette may exceed the layout capsule. Retain real source pixels there.
        val margin=ceil((if(p.style==LiquidGlassStyle.DEFAULT)30f else 40f)*density).toInt()
        try{
            val effect=shader?:RuntimeShader(LENS).also{shader=it}
            if(lastParams!=p||lastWidth!=w||lastHeight!=h){
                effect.setFloatUniform("size",w.toFloat(),h.toFloat())
                effect.setFloatUniform("margin",margin.toFloat())
                effect.setFloatUniform("bevel",p.bevelDp*density)
                effect.setFloatUniform("refractPx",p.refractionDp*density)
                effect.setFloatUniform("strength",p.strength)
                effect.setFloatUniform("dark",if(p.dark)1f else 0f)
                effect.setFloatUniform("opacity",p.opacity)
                effect.setFloatUniform("selection",p.lensX,p.lensY,p.lensHalfWidth,p.lensHalfHeight)
                effect.setFloatUniform("selectionOverflow",if(p.selectionOverflow)1f else 0f)
                effect.setFloatUniform("selectionRefractPx",p.selectionRefractionDp*density)
                effect.setFloatUniform("touch",p.touchX,p.touchY)
                effect.setFloatUniform("press",if(p.pressed)1f else 0f)
                effect.setFloatUniform("style",p.style.ordinal.toFloat())
                effect.setFloatUniform("density",density)
                // Gaussian-blur the independent background BEFORE refraction/tint and silhouette.
                // Blurring the final view would also soften the rim and child labels/icons.
                node.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(effect,"content"),backdrop(p.style)))
                lastParams=p;lastWidth=w;lastHeight=h
            }
            glass.getLocationOnScreen(ownLocation);source.getLocationOnScreen(sourceLocation)
            val dx=ownLocation[0]-sourceLocation[0];val dy=ownLocation[1]-sourceLocation[1]
            node.setPosition(-margin,-margin,w+margin,h+margin)
            val recording=node.beginRecording(w+margin*2,h+margin*2)
            try{
                recording.translate((margin-dx).toFloat(),(margin-dy).toFloat())
                // Hardware display-list recording only. Target is a sibling subtree excluding this glass.
                // The source records its child RenderNodes. Toolbar source already contains its header blur.
                source.draw(recording)
            }finally{node.endRecording()}
            if(p.style!=LiquidGlassStyle.DEFAULT)drawShadow(canvas,w,h,p.dark)
            canvas.drawRenderNode(node)
            return true
        }catch(e:RuntimeException){
            failure=e.javaClass.simpleName+": "+e.message
            Log.e("LiquidGlass","GPU lens unavailable; using BlurView compatibility path",e)
            return false
        }
    }
    fun release(){
        node.discardDisplayList();shadow?.discardDisplayList();shadow=null
        shader=null;lastParams=null;shadowWidth=0;shadowHeight=0
    }

    private companion object{
        const val LENS="""
            uniform shader content;
            uniform float2 size;
            uniform float margin;
            uniform float bevel;
            uniform float refractPx;
            uniform float strength;
            uniform float dark;
            uniform float opacity;
            uniform float4 selection;
            uniform float selectionOverflow;
            uniform float selectionRefractPx;
            uniform float2 touch;
            uniform float press;
            uniform float style;
            uniform float density;

            float roundedBox(float2 p,float2 halfSize,float r){
                float2 q=abs(p)-halfSize+r;
                return length(max(q,float2(0.0)))+min(max(q.x,q.y),0.0)-r;
            }
            float shell(float2 p){return roundedBox(p-size*0.5,size*0.5,min(size.x,size.y)*0.5);}
            float lens(float2 p){return roundedBox(p-selection.xy,selection.zw,max(1.0,min(selection.z,selection.w)));}
            float shape(float2 p){
                if(selectionOverflow>0.5&&selection.z>1.0&&selection.w>1.0)return min(shell(p),lens(p));
                return shell(p);
            }
            float2 shellNormal(float2 p){
                float2 n=float2(shape(p+float2(1.0,0.0))-shape(p-float2(1.0,0.0)),
                                shape(p+float2(0.0,1.0))-shape(p-float2(0.0,1.0)));
                return n/max(length(n),0.0001);
            }
            float2 lensNormal(float2 p){
                float2 n=float2(lens(p+float2(1.0,0.0))-lens(p-float2(1.0,0.0)),
                                lens(p+float2(0.0,1.0))-lens(p-float2(0.0,1.0)));
                return n/max(length(n),0.0001);
            }

            // Displacement is zero at refraction height and grows toward the real capsule edge.
            float circularSlope(float d,float height){
                float x=clamp(1.0+d/height,0.0,1.0);
                return 1.0-sqrt(1.0-x*x);
            }

            float normalCdf(float x){
                // Express the tanh approximation with exp, available in Android RuntimeShader.
                float z=clamp(x,-5.0,5.0);
                return 1.0/(1.0+exp(-1.59576912*(z+0.044715*z*z*z)));
            }

            half4 commentGlass(float2 coord,float2 p,float d,float cover,float2 n){
                float2 offset=-n*circularSlope(d,bevel)*refractPx;
                if(selection.z>1.0&&selection.w>1.0){
                    float sd=lens(p);
                    // The movable selection refracts near its inner edge with gesture-dependent strength.
                    offset-=lensNormal(p)*circularSlope(sd,7.0*density)
                        *selectionRefractPx*(1.0-smoothstep(0.0,density,sd));
                }
                float2 hi=size+float2(margin*2.0-1.0);
                float4 sample=content.eval(clamp(coord+offset,float2(1.0),hi));
                float3 surface=float3(1.0-dark);
                float3 col=mix(sample.rgb+surface*(1.0-sample.a),surface,mix(0.60,0.28,dark));

                // A 0.5dp inward stroke with the 0.25dp reference blur, in additive white.
                float sigma=0.57735*0.25*density+0.5;
                float edge=normalCdf((d+0.5*density)/sigma)-normalCdf(d/sigma);
                float highlight=0.38;
                if(style>1.5){
                    highlight=0.50*abs(dot(n,float2(0.70710678,0.70710678)));
                }
                float innerShadow=dark*0.15*0.12*exp(min(0.0,d)/(4.0*density));
                col=clamp(col*(1.0-innerShadow)+edge*highlight*mix(1.0,0.58,dark),0.0,1.0);
                cover*=opacity;
                return half4(half3(col*cover),half(cover));
            }

            half4 main(float2 coord){
                float2 p=coord-float2(margin);
                float d=shape(p);
                float cover=clamp(0.5-d/1.2,0.0,1.0);
                if(cover<0.004)return half4(0.0);
                float2 n=shellNormal(p);
                if(style>0.5)return commentGlass(coord,p,d,cover,n);
                float t=clamp(-d/max(bevel,1.0),0.0,1.0);
                float slope=(pow(1.0+4.0*t,-1.7)-pow(5.0,-1.7))/(1.0-pow(5.0,-1.7));
                // Inward normal sampling produces a compressed, mirrored background edge band.
                float2 offset=-n*slope*refractPx;
                // Mild central magnification joins the bevel continuously, keeping the middle clear.
                offset-=(p-size*0.5)*0.005*(1.0-slope);
                float innerHair=0.0;
                if(selection.z>1.0&&selection.w>1.0){
                    float sd=lens(p);
                    float edge=clamp(1.0-abs(sd)/(bevel*0.7),0.0,1.0);
                    float2 sn=lensNormal(p);
                    // A real 1.18x centre lens at full hold; fade it continuously into its bevel.
                    float core=smoothstep(0.0,max(bevel*0.85,1.0),-sd);
                    float magnification=1.035+0.145*strength;
                    offset-=(p-selection.xy)*(1.0-1.0/magnification)*core;
                    offset-=sn*edge*edge*selectionRefractPx;
                    innerHair=pow(edge,6.0)*pow(abs(dot(sn,normalize(float2(-0.7,-0.72)))),4.5);
                }
                if(press>0.5){
                    float2 delta=p-touch;
                    float amount=exp(-dot(delta,delta)/max(1.0,size.y*size.y*0.55));
                    offset-=delta*amount*0.035;
                }
                float2 lo=float2(1.0);
                float2 hi=size+float2(margin*2.0-1.0);
                float dispersion=0.03+0.012*strength;
                float4 red=content.eval(clamp(coord+offset*(1.0-dispersion*slope),lo,hi));
                float4 green=content.eval(clamp(coord+offset,lo,hi));
                float4 blue=content.eval(clamp(coord+offset*(1.0+dispersion*slope),lo,hi));
                float3 neutral=mix(float3(0.98,0.99,1.0),float3(0.11,0.125,0.15),dark);
                float3 col=float3(red.r,green.g,blue.b)+(1.0-float3(red.a,green.a,blue.a))*neutral;
                float lum=dot(col,float3(0.2126,0.7152,0.0722));
                col=clamp(mix(float3(lum),col,1.12),float3(0.0),float3(1.0));
                // Readability: stronger body tint retains the moving lens without busy text beneath.
                col=mix(col,neutral,mix(0.34,0.44,dark)*(1.0-0.35*slope));
                float2 light=normalize(float2(-0.7+0.22*strength,-0.72));
                float facing=dot(n,light);
                float lobe=pow(abs(facing),4.5);
                float hair=clamp(1.0-abs(d+1.0)/1.8,0.0,1.0);
                float glow=pow(clamp(1.0-(-d-2.0)/max(bevel*0.45,2.0),0.0,1.0),1.5);
                float spec=(hair*0.65*lobe+glow*0.11*max(facing,0.0)+innerHair*0.15)*(1.0+0.45*strength);
                // Counterlight gives the glass a visible bevel even against a near-white list.
                float shadow=glow*(0.035+0.12*max(-facing,0.0))+innerHair*0.055;
                col=clamp(col+float3(spec)-shadow*float3(0.87,0.94,1.0),float3(0.0),float3(1.0));
                cover*=opacity;
                return half4(half3(col*cover),half(cover));
            }
        """
    }
}
