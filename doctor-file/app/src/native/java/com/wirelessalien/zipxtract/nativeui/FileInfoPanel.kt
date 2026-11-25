package com.wirelessalien.zipxtract.nativeui

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.wirelessalien.zipxtract.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

/** A file properties sheet, distinct from an error/notice dialog. */
object FileInfoPanel {
    fun show(activity:Activity,file:File,icon:String,size:String,onLocation:()->Unit):MotionDialog{
        val c=activity;val p=Palette(c)
        val visible=Rect().also{activity.window.decorView.getWindowVisibleDisplayFrame(it)}
        val visibleWidth=visible.width().takeIf{it>0}?:c.resources.displayMetrics.widthPixels
        val visibleHeight=visible.height().takeIf{it>0}?:c.resources.displayMetrics.heightPixels
        val dialog=MotionDialog(c,R.style.NativeTheme)
        val surface=LinearLayout(c).apply{
            id=R.id.file_info_panel;orientation=LinearLayout.VERTICAL
            setPadding(c.dp(24),c.dp(24),c.dp(24),c.dp(20))
            background=p.background(p.page,c.dp(30).toFloat());clipToOutline=true
        }
        val body=LinearLayout(c).apply{orientation=LinearLayout.VERTICAL}
        body.addView(ImageView(c).apply{setImageDrawable(Art.drawable(c,icon));contentDescription=null},LinearLayout.LayoutParams(c.dp(66),c.dp(66)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=c.dp(18)})
        body.addView(c.label(file.name,20f,p.text).apply{
            id=R.id.file_info_name;gravity=Gravity.CENTER
            breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE;setLineSpacing(c.dp(3).toFloat(),1f)
        },LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=c.dp(24)})
        // A fixed dp label column wraps at large system fonts. Measure its actual font metrics.
        val titleWidth=ceil(listOf("位置：","大小：","时间：","可读：","可写：","隐藏：")
            .maxOf{c.label(it,16f,p.text).paint.measureText(it)}).toInt()+c.dp(7)
        fun field(title:String,value:String,link:Boolean=false){
            val row=LinearLayout(c).apply{gravity=Gravity.TOP;setPadding(0,c.dp(6),0,c.dp(6))}
            row.addView(c.label("$title：",16f,p.text).apply{gravity=Gravity.TOP},LinearLayout.LayoutParams(titleWidth,-2))
            row.addView(c.label(value,16f,if(link)p.blue else p.text).apply{
                gravity=Gravity.TOP;setLineSpacing(c.dp(3).toFloat(),1f)
                breakStrategy=android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
                if(link){id=R.id.file_info_location;minimumHeight=c.dp(44);isClickable=true;isFocusable=true
                    contentDescription="打开所在位置：$value";setOnClickListener{dialog.close(onLocation)}}
            },LinearLayout.LayoutParams(0,-2,1f))
            body.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
        field("位置",file.absolutePath,true)
        field("大小",size)
        val thisYear=Calendar.getInstance().get(Calendar.YEAR)
        val fileYear=Calendar.getInstance().apply{timeInMillis=file.lastModified()}.get(Calendar.YEAR)
        field("时间",SimpleDateFormat(if(fileYear==thisYear)"MM-dd HH:mm"else"yyyy/MM/dd HH:mm",Locale.getDefault()).format(Date(file.lastModified())))
        field("可读",if(file.canRead())"是"else"否")
        field("可写",if(file.canWrite())"是"else"否")
        field("隐藏",if(file.isHidden)"是"else"否")
        val width=minOf((visibleWidth-c.dp(24)).coerceAtLeast(1),c.dp(500))
        body.measure(View.MeasureSpec.makeMeasureSpec((width-c.dp(48)).coerceAtLeast(1),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        // The button is outside the scroller and is always reachable, also in landscape/split screen.
        val available=((visibleHeight*.92f).toInt()-c.dp(116)).coerceAtLeast(1)
        surface.addView(ScrollView(c).apply{isVerticalScrollBarEnabled=false;addView(body)},LinearLayout.LayoutParams(-1,minOf(body.measuredHeight,available)))
        surface.addView(c.label("知道了",17f,p.text).apply{
            id=R.id.file_info_close;gravity=Gravity.CENTER;background=p.background(p.surface,c.dp(18).toFloat())
            isClickable=true;isFocusable=true;setOnClickListener{dialog.close{}}
        },LinearLayout.LayoutParams(-1,c.dp(54)).apply{topMargin=c.dp(18)})
        dialog.showPanel(surface,width,bottom=true,dimAmount=.2f)
        return dialog
    }
}
