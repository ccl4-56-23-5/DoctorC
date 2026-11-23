package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.nativeui.DateSections
import com.wirelessalien.zipxtract.nativeui.ViewportAnchor
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Calendar

class NavigationPresentationTest {
    private fun date(year:Int,month:Int,day:Int,hour:Int=12)=Calendar.getInstance().apply{
        clear();set(year,month-1,day,hour,0,0)
    }.timeInMillis
    @Test fun sameFileRemainsAnchorAfterInsertions(){
        val a=ViewportAnchor(listOf("header","a","b","c"),2,-17)
        assertEquals(3,a.resolve(listOf("header","new","a","b","c")))
        assertEquals(1,a.resolve(listOf("header","b","c")))
    }
    @Test fun deletedAnchorUsesNextSurvivorThenPrevious(){
        val a=ViewportAnchor(listOf("header","a","b","c"),2,-17)
        assertEquals(2,a.resolve(listOf("header","a","c")))
        assertEquals(1,a.resolve(listOf("header","a")))
    }
    @Test fun replacedOrEmptyListClampsAnchor(){
        val a=ViewportAnchor(listOf("a","b","c"),2,0)
        assertEquals(0,a.resolve(emptyList()));assertEquals(0,a.resolve(listOf("different")))
    }
    @Test fun calendarLabelsCrossYearAndMidnightCorrectly(){
        val now=date(2027,1,1,0)
        assertEquals("今天",DateSections.title(now,now))
        assertEquals("昨天",DateSections.title(date(2026,12,31,23),now))
        assertEquals("2天前",DateSections.title(date(2026,12,30),now))
        assertEquals("2025年10月2日",DateSections.title(date(2025,10,2),now))
    }
    @Test fun groupsCountCivilDaysAndRemainNewestFirst(){
        val root=Files.createTempDirectory("zipdoctorc-date-").toFile()
        try{
            val old=java.io.File(root,"old.png").apply{writeText("fixture");assertTrue(setLastModified(date(2025,11,14)))}
            val one=java.io.File(root,"one.png").apply{writeText("fixture");assertTrue(setLastModified(date(2026,11,3,1)))}
            val two=java.io.File(root,"two.png").apply{writeText("fixture");assertTrue(setLastModified(date(2026,11,3,23)))}
            val groups=DateSections.group(listOf(old,one,two),date(2026,11,3))
            assertEquals(2,groups.size);assertEquals("今天",groups[0].title)
            assertEquals(listOf(two,one),groups[0].files);assertEquals(listOf(old),groups[1].files)
        }finally{root.deleteRecursively()}
    }
    @Test fun videoTimeDoesNotWrapAtOneHour(){
        assertEquals("0:00",DateSections.duration(-1));assertEquals("0:09",DateSections.duration(9999))
        assertEquals("2:03",DateSections.duration(123000));assertEquals("1:00:01",DateSections.duration(3601000))
    }
}
