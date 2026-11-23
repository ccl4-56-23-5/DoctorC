package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.nativeui.DockGeometry
import org.junit.Assert.*
import org.junit.Test

class DockGeometryTest {
    @Test fun referenceViewportMatchesTheProvidedPhysicalGeometry(){
        val g=DockGeometry.calculate(1200,3.125f)
        assertEquals(DockGeometry.Box(225,0,540,174),g.capsule)
        assertEquals(DockGeometry.Box(801,0,174,174),g.search)
        assertEquals(249,g.hostHeight)
        assertEquals(75,g.bottomClearance)
        assertTrue(g.iconSize+g.labelHeight+g.iconLabelGap+(8*3.125f).toInt()<=g.capsule.height)
    }
    @Test fun searchRemainsCircularAndBothIndependentSurfacesStayInsideSafeWidth(){
        for(density in listOf(1f,2f,2.75f,3.125f,4f)){
            for(widthDp in listOf(240,280,320,360,384,412,600,800)){
                for(font in listOf(1f,1.3f,2f)){
                    val width=(widthDp*density).toInt()
                    val g=DockGeometry.calculate(width,density,font)
                    val circle=g.search!!
                    assertEquals(circle.width,circle.height)
                    assertEquals(g.capsule.height,circle.height)
                    assertTrue(g.capsule.left>=0)
                    assertTrue(g.capsule.right<circle.left)
                    assertTrue(circle.right<=width)
                    assertTrue(g.capsule.width>g.capsule.height*3)
                    assertTrue(g.selectorInset+g.selectorTravel+g.selectorWidth<=g.capsule.width)
                    assertTrue(g.selectorHeight<=g.capsule.height)
                }
            }
        }
    }
    @Test fun safeBottomIsSubtractedOnceAndActionModesDoNotReserveSearchSpace(){
        val tabs=DockGeometry.calculate(1200,3.125f,bottomInset=72)
        assertEquals(3,tabs.bottomClearance)
        assertEquals(177,tabs.hostHeight)
        val actions=DockGeometry.calculate(1200,3.125f,1.3f,DockGeometry.Mode.ACTIONS,4,bottomInset=72)
        assertNull(actions.search)
        assertEquals(50,actions.capsule.left)
        assertEquals(1150,actions.capsule.right)
        assertTrue(actions.capsule.width/4>=150)
        assertTrue(actions.capsule.height>=actions.iconSize+actions.labelHeight+actions.iconLabelGap)
    }
    @Test fun tabletLandscapeKeepsTheSameControlHeightsAndCentersSixActions(){
        val portrait = DockGeometry.calculate(760, 1f, bottomInset = 20)
        val landscape = DockGeometry.calculate(1260, 1f, bottomInset = 20)
        assertEquals(portrait.search!!.width, landscape.search!!.width)
        assertEquals(portrait.hostHeight, landscape.hostHeight)
        assertTrue(landscape.search.width <= 62)
        val actions = DockGeometry.calculate(1260, 1f, mode = DockGeometry.Mode.ACTIONS, actionCount = 6)
        assertEquals(544, actions.capsule.width)
        assertEquals(1260, actions.capsule.left * 2 + actions.capsule.width)
    }
}
