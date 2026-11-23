package com.wirelessalien.zipxtract.nativeui

import kotlin.math.roundToInt

/** Both pages share one pixel boundary. They never occupy the same screen region. */
object MotionFrames {
    data class Pages(val oldX:Int,val newX:Int)
    fun pages(width:Int,progress:Float,direction:Int):Pages{
        val w=width.coerceAtLeast(1);val step=(w*progress.coerceIn(0f,1f)).roundToInt()
        return if(direction>=0)Pages(-step,w-step)else Pages(step,-w+step)
    }
    fun slide(distance:Int,visibility:Float):Int=(distance.coerceAtLeast(1)*(1-visibility.coerceIn(0f,1f))).roundToInt()

    /** Finish transferring opacity before Android removes the transparent overlay window. */
    fun sourceReveal(progress: Float): Float {
        val fraction = (1f - progress / .22f).coerceIn(0f, 1f)
        return fraction * fraction * (3f - 2f * fraction)
    }
}
