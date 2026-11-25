/* ImageGen raster artwork; rename is the intentional lightweight local-clock Canvas exception. */
package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.wirelessalien.zipxtract.R

object Art {
    private val resources=mapOf(
        "folder" to R.drawable.art_folder,
        "archive" to R.drawable.art_archive,
        "document" to R.drawable.art_document,
        "image" to R.drawable.art_image,
        "video" to R.drawable.art_video,
        "play" to R.drawable.art_play,
        "music" to R.drawable.art_music,
        "apk" to R.drawable.art_apk,
        "extract" to R.drawable.art_extract,
        "back" to R.drawable.art_back,
        "close" to R.drawable.art_close,
        "search" to R.drawable.art_search,
        "more" to R.drawable.art_more,
        "sort" to R.drawable.art_sort,
        "newfolder" to R.drawable.art_newfolder,
        "all" to R.drawable.art_all,
        "chevron" to R.drawable.art_chevron,
        "copy" to R.drawable.art_copy,
        "move" to R.drawable.art_move,
        "transfer" to R.drawable.art_transfer,
        "delete" to R.drawable.art_delete,
        "eye" to R.drawable.art_eye,
        "phone" to R.drawable.art_phone,
        "download" to R.drawable.art_download,
        "favorite" to R.drawable.art_favorite,
        "recent" to R.drawable.art_recent,
        "browse" to R.drawable.art_browse,
        "compress" to R.drawable.art_compress,
        "check" to R.drawable.art_check,
        "circle" to R.drawable.art_circle,
        "notification" to R.drawable.art_notification
    )
    private val neutral=setOf("back","close","search","more","sort","newfolder","all","chevron","copy","move","transfer","delete","eye","phone","download","favorite","recent","browse","compress","circle")
    val keys:Set<String> get()=resources.keys+"rename"
    fun drawable(context:Context,name:String):Drawable {
        val key=when(name){"file"->"document";"zip"->"archive";"folderSolid"->"folder";"tasks"->"recent";else->name}
        if(key=="rename")return ClockRenameDrawable(dark=Palette(context).dark)
        val result=ContextCompat.getDrawable(context,resources[key]?:R.drawable.art_document)!!.mutate()
        if(Palette(context).dark&&key in neutral)result.colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(-1f,0f,0f,0f,255f,0f,-1f,0f,0f,255f,0f,0f,-1f,0f,255f,0f,0f,0f,1f,0f)))
        return result
    }
}
