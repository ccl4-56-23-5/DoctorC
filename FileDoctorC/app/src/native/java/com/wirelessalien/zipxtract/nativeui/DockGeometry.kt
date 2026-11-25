package com.wirelessalien.zipxtract.nativeui

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pixel geometry derived from the supplied 1200px-wide file-manager reference:
 * 540x174 capsule, 174x174 search circle, 36px gap, 75px physical bottom clearance.
 *
 * This model has no View/blur/animation ownership. All figures use the actual safe
 * viewport width, so a 384dp phone does not get the oversized former 208x66dp dock.
 */
object DockGeometry {
    enum class Mode { TABS, ACTIONS }
    data class Box(val left:Int,val top:Int,val width:Int,val height:Int) {
        val right:Int get()=left+width
        val bottom:Int get()=top+height
    }
    data class Layout(
        val hostHeight:Int,
        val capsule:Box,
        val search:Box?,
        val selectorInset:Int,
        val selectorWidth:Int,
        val selectorHeight:Int,
        val selectorTravel:Int,
        val iconSize:Int,
        val labelHeight:Int,
        val labelSp:Float,
        val iconLabelGap:Int,
        val bottomClearance:Int
    )
    private const val CAPSULE_ASPECT=540f/174f
    private const val LABEL_SP=13f

    /** bottomInset is the part already removed by the activity's safe-area padding. */
    fun calculate(
        availableWidth:Int,
        density:Float,
        fontScale:Float=1f,
        mode:Mode=Mode.TABS,
        actionCount:Int=2,
        bottomInset:Int=0
    ):Layout {
        val width=availableWidth.coerceAtLeast(1)
        val d=density.takeIf{it.isFinite()&&it>0f}?:1f
        val f=fontScale.takeIf{it.isFinite()&&it>0f}?:1f
        fun dp(value:Float)=(value*d).roundToInt().coerceAtLeast(0)
        val referenceWidth=min(width,dp(420f))
        val referenceHeight=(referenceWidth*.145f).roundToInt().coerceAtLeast(1)
        val minTouch=dp(48f).coerceAtLeast(1)
        val baseHeight=max(referenceHeight,minTouch)
        val baseIcon=min((baseHeight*.5f).roundToInt(),dp(30f))
        val labelHeight=ceil(LABEL_SP*d*f*1.15f).toInt().coerceAtLeast(1)
        val labelGap=dp(2f)
        val verticalPadding=2*dp(4f)
        val requiredHeight=baseIcon+labelHeight+labelGap+verticalPadding
        val requestedHeight=max(baseHeight,requiredHeight)
        val physicalClearance=(referenceWidth*.0625f).roundToInt()
        val bottomClearance=(physicalClearance-bottomInset.coerceAtLeast(0)).coerceAtLeast(0)

        val capsule:Box
        val search:Box?
        val height:Int
        if(mode==Mode.TABS){
            val nominalGap=(referenceWidth*.03f).roundToInt().coerceAtLeast(1)
            val margin=min(dp(16f),width/16)
            // Keep the circle and capsule aspect exact unless the viewport physically
            // cannot accommodate their minimum touch dimensions.
            val cap=floor((width-2*margin-nominalGap)/(CAPSULE_ASPECT+1f)).toInt().coerceAtLeast(1)
            height=min(requestedHeight,cap)
            val capsuleWidth=(height*CAPSULE_ASPECT).roundToInt().coerceAtLeast(1)
            val gap=min(nominalGap,(width-capsuleWidth-height).coerceAtLeast(0))
            val groupWidth=capsuleWidth+gap+height
            val left=((width-groupWidth)/2).coerceAtLeast(0)
            capsule=Box(left,0,capsuleWidth,height)
            search=Box(capsule.right+gap,0,height,height)
        }else{
            height=requestedHeight
            val actions=actionCount.coerceAtLeast(1)
            val letters=if(actions<=2)4 else 2
            val minActionWidth=max(minTouch,ceil(LABEL_SP*d*f*letters).toInt()+2*dp(4f))
            val margin=min(dp(16f),((width-minActionWidth*actions)/2).coerceAtLeast(0))
            val capsuleWidth=(width-2*margin).coerceIn(1,dp(544f).coerceAtLeast(1))
            capsule=Box((width-capsuleWidth)/2,0,capsuleWidth,height)
            search=null
        }
        val inset=min((height*8f/174f).roundToInt().coerceAtLeast(1),max(0,(height-1)/2))
        val innerWidth=(capsule.width-2*inset).coerceAtLeast(1)
        val selectorWidth=(innerWidth/2).coerceAtLeast(1)
        val icon=min(baseIcon,(height-labelHeight-labelGap-verticalPadding).coerceAtLeast(0))
        return Layout(
            hostHeight=height+bottomClearance,
            capsule=capsule,
            search=search,
            selectorInset=inset,
            selectorWidth=selectorWidth,
            selectorHeight=(height-2*inset).coerceAtLeast(1),
            selectorTravel=innerWidth-selectorWidth,
            iconSize=icon,
            labelHeight=labelHeight,
            labelSp=LABEL_SP,
            iconLabelGap=labelGap,
            bottomClearance=bottomClearance
        )
    }
}
