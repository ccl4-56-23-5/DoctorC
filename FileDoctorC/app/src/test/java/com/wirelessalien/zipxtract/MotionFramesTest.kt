package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.nativeui.MotionFrames
import org.junit.Assert.*
import org.junit.Test

class MotionFramesTest {
    @Test fun opposingPagesPartitionEveryPixelWithoutAnOverlap(){
        for(width in listOf(1,2,3,320,1080,1600))for(direction in listOf(-1,1))for(step in 0..1000){
            val f=MotionFrames.pages(width,step/1000f,direction)
            val left=minOf(f.oldX,f.newX);val right=maxOf(f.oldX,f.newX)
            assertEquals("shared boundary has no gap or overlap",left+width,right)
            assertTrue("both edges remain covered",left<=0&&right+width>=width)
            assertTrue("only one page covers an interior pixel",!(f.oldX<width/2f&&f.oldX+width>width/2f&&f.newX<width/2f&&f.newX+width>width/2f))
        }
    }
    @Test fun pageEndpointsAndDirectionRemainCorrectUnderClampedProgress(){
        for(direction in listOf(-1,1)){
            val begin=MotionFrames.pages(400,-1f,direction);val end=MotionFrames.pages(400,2f,direction)
            assertEquals(0,begin.oldX);assertEquals(0,end.newX)
            assertEquals(if(direction==1)400 else -400,begin.newX)
            assertEquals(if(direction==1)-400 else 400,end.oldX)
        }
    }
    @Test fun rigidPanelTravelIsMonotonicAndHasNoSubpixelOutput(){
        var previous=800
        for(i in 0..1000){val y=MotionFrames.slide(800,i/1000f);assertTrue(y<=previous&&y in 0..800);previous=y}
        assertEquals(800,MotionFrames.slide(800,0f));assertEquals(0,MotionFrames.slide(800,1f))
    }
}
