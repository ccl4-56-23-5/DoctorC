package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.bumptech.glide.Glide
import com.wirelessalien.zipxtract.R
import java.io.File

/** Category presentation only: data ordering, selection and navigation stay with the screen. */
object CategoryViews {
    fun heading(context:Context,title:String,count:Int,collapsed:Boolean,onClick:()->Unit):View =
        heading(context,title,count,collapsed,true,onClick)

    fun heading(context:Context,title:String,count:Int,collapsed:Boolean,showDivider:Boolean,onClick:()->Unit):View{
        val c=context;val p=Palette(c)
        return LinearLayout(c).apply{
            orientation=LinearLayout.VERTICAL
            // The RecyclerView / row wrapper owns horizontal margins for both lists and grids.
            setPadding(0,if(showDivider)c.dp(19)else c.dp(2),0,c.dp(18))
            if(showDivider)addView(View(c).apply{setBackgroundColor(p.line)},LinearLayout.LayoutParams(-1,c.dp(1)).apply{bottomMargin=c.dp(17)})
            val row=LinearLayout(c).apply{
                gravity=Gravity.CENTER_VERTICAL;minimumHeight=c.dp(46)
                isClickable=true;isFocusable=true
                contentDescription="$title，$count 项，${if(collapsed)"已收起，展开"else"已展开，收起"}"
                setOnClickListener{onClick()}
            }
            row.addView(c.label("$title  |  ${count}项",16f,if(p.dark)p.secondary else 0xff8f9ba9.toInt()).apply{
                maxLines=2;ellipsize=TextUtils.TruncateAt.END
            },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(ImageView(c).apply{
                setImageDrawable(Art.drawable(c,"chevron"));imageAlpha=155
                rotation=if(collapsed)90f else -90f
                scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(c.dp(11),c.dp(11),c.dp(11),c.dp(11))
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(c.dp(42),c.dp(42)))
            addView(row,LinearLayout.LayoutParams(-1,-2))
        }
    }

    fun thumbnail(context:Context,file:File,video:Boolean,durationMillis:Long,selected:Boolean,
                  selectionMode:Boolean,onClick:()->Unit,onLongSelect:()->Unit):View{
        val c=context;val p=Palette(c)
        val tile=SquareFrameLayout(c).apply{
            tag=file.path
            background=p.background(p.surface,c.dp(8).toFloat());clipToOutline=true
            isClickable=true;isLongClickable=true;isFocusable=true;isSelected=selected
            accessible(buildString{
                append(file.name);append(if(video)"，视频"else"，图片")
                if(video&&durationMillis>0){append("，时长 ");append(DateSections.duration(durationMillis))}
                if(selectionMode)append(if(selected)"，已选择"else"，未选择")
            })
            setOnClickListener{onClick()};setOnLongClickListener{onLongSelect();true}
        }
        val image=ImageView(c).apply{
            scaleType=ImageView.ScaleType.CENTER_CROP
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        tile.addView(image,FrameLayout.LayoutParams(-1,-1))
        val request=Glide.with(c).load(file).disallowHardwareConfig().centerCrop()
            .placeholder(Art.drawable(c,if(video)"video"else"image"))
            .error(Art.drawable(c,if(video)"video"else"image"))
        if(video)request.frame(1_000_000L)
        request.into(image)
        if(video){
            tile.addView(View(c).apply{
                background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0x60000000))
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },FrameLayout.LayoutParams(-1,c.dp(33),Gravity.BOTTOM))
            val duration=LinearLayout(c).apply{gravity=Gravity.CENTER_VERTICAL}
            duration.addView(ImageView(c).apply{
                setImageDrawable(Art.drawable(c,"play"));scaleType=ImageView.ScaleType.FIT_CENTER
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(c.dp(20),c.dp(20)))
            duration.addView(c.label(if(durationMillis>0)DateSections.duration(durationMillis)else"--:--",
                if(durationMillis>=3_600_000L)10f else 12f,Color.WHITE).apply{
                id=R.id.media_duration;maxLines=1;ellipsize=TextUtils.TruncateAt.END
                setShadowLayer(c.dp(1).toFloat(),0f,c.dp(1).toFloat(),0x80000000.toInt())
            },LinearLayout.LayoutParams(-2,-2).apply{leftMargin=c.dp(3)})
            tile.addView(duration,FrameLayout.LayoutParams(-2,c.dp(24),Gravity.BOTTOM or Gravity.LEFT).apply{
                leftMargin=c.dp(4);rightMargin=c.dp(3);bottomMargin=c.dp(3)
            })
        }
        if(selectionMode){
            if(selected)tile.foreground=p.background(0x183478f6,c.dp(8).toFloat()).apply{setStroke(c.dp(2),p.blue)}
            tile.addView(ImageView(c).apply{
                setImageDrawable(Art.drawable(c,if(selected)"check"else"circle"));scaleType=ImageView.ScaleType.FIT_CENTER
                background=p.background(if(p.dark)0xee24262b.toInt()else 0xeeffffff.toInt(),c.dp(13).toFloat())
                setPadding(c.dp(2),c.dp(2),c.dp(2),c.dp(2));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },FrameLayout.LayoutParams(c.dp(24),c.dp(24),Gravity.TOP or Gravity.RIGHT).apply{topMargin=c.dp(4);rightMargin=c.dp(4)})
        }
        return tile
    }

    /** GridLayoutManager determines the width; height always follows that exact cell width. */
    private class SquareFrameLayout(context:Context):FrameLayout(context){
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
            val width=if(View.MeasureSpec.getMode(widthMeasureSpec)==View.MeasureSpec.UNSPECIFIED)context.dp(84)
                else View.MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY))
        }
    }
}
