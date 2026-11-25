package com.wirelessalien.zipxtract.nativeui

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Civil-day grouping shared by all file categories; no UI or persisted settings. */
object DateSections {
    data class Section(val key:String,val title:String,val files:List<File>)
    fun key(time:Long):String=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(time)
    fun title(time:Long,now:Long=System.currentTimeMillis()):String{
        val date=key(time)
        val cal=Calendar.getInstance().apply{timeInMillis=now}
        for(day in 0..6){
            if(key(cal.timeInMillis)==date)return when(day){0->"今天";1->"昨天";else->"${day}天前"}
            cal.add(Calendar.DAY_OF_YEAR,-1)
        }
        val current=Calendar.getInstance().apply{timeInMillis=now}.get(Calendar.YEAR)
        val year=Calendar.getInstance().apply{timeInMillis=time}.get(Calendar.YEAR)
        return SimpleDateFormat(if(year==current)"M月d日"else"yyyy年M月d日",Locale.CHINA).format(time)
    }
    fun group(files:List<File>,now:Long=System.currentTimeMillis()):List<Section> = files
        .sortedByDescending{it.lastModified()}.groupBy{key(it.lastModified())}
        .map{(key,rows)->Section(key,title(rows.first().lastModified(),now),rows)}
    fun duration(millis:Long):String{
        val seconds=(millis.coerceAtLeast(0)/1000)
        return if(seconds>=3600)String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
        else String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60)
    }
}
