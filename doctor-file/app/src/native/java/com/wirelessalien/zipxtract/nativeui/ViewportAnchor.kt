package com.wirelessalien.zipxtract.nativeui

/** Stable identity plus visible pixel offset, with surviving neighbours when a file disappears. */
data class ViewportAnchor(val keys:List<String>,val position:Int,val offset:Int){
    fun resolve(newKeys:List<String>):Int{
        if(newKeys.isEmpty())return 0
        val positions=HashMap<String,Int>(newKeys.size)
        for((index,key) in newKeys.withIndex())positions.putIfAbsent(key,index)
        for(i in position.coerceAtLeast(0) until keys.size){positions[keys[i]]?.let{return it}}
        for(i in minOf(position-1,keys.lastIndex) downTo 0){positions[keys[i]]?.let{return it}}
        return position.coerceIn(0,newKeys.lastIndex)
    }
}
