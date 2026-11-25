/* Rounded text-first popovers, 2026-11-03. GPL-3.0-or-later. */
package com.wirelessalien.zipxtract.nativeui

import android.app.Activity
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import com.wirelessalien.zipxtract.R
import androidx.core.view.ViewCompat

/** Remember the real invoking view, including controls inside a dialog window. */
fun View.menuAnchor(){
    (context as? Activity)?.window?.decorView?.setTag(R.id.native_menu_anchor,this)
}

class NativePopups(private val activity:Activity){
    data class Row(val title: String, val subtitle: String? = null, val removeDescription: String? = null)

    private var active:MotionPopup?=null
    fun dismiss(){active?.dispose();active=null}

    fun show(title:String,items:List<String>,checked:Int=-1,selected:(Int)->Unit){
        showRows(title, items.map { Row(it) }, checked, selected = selected)
    }

    fun showRows(title: String, items: List<Row>, checked: Int = -1,
                 onRemove: ((Int) -> Unit)? = null, selected: (Int) -> Unit) {
        dismiss()
        val c=activity;val colors=Palette(c)
        val decor = c.window.decorView
        val anchor = (decor.getTag(R.id.native_menu_anchor) as? View)?.takeIf { it.isAttachedToWindow }
        decor.setTag(R.id.native_menu_anchor, null)
        val parent=anchor?.rootView?:c.window.decorView
        if(c.isFinishing||c.isDestroyed||!parent.isAttachedToWindow)return
        val frame=Rect().also{parent.getWindowVisibleDisplayFrame(it)}
        if(frame.width()<=0||frame.height()<=0)frame.set(0,0,c.resources.displayMetrics.widthPixels,c.resources.displayMetrics.heightPixels)
        val width=minOf(c.dp(224),(frame.width()-c.dp(24)).coerceAtLeast(1))
        val source = Rect()
        val location = IntArray(2)
        val hasAnchor = anchor != null && anchor.getLocalVisibleRect(source)
        if (hasAnchor) {
            anchor!!.getLocationOnScreen(location)
            source.offset(location[0], location[1])
        }
        val safeTop = frame.top + c.dp(8)
        val safeBottom = frame.bottom - c.dp(12)
        // Keep one edge aligned with the invoking button while the panel grows into available space.
        val above = source.bottom - safeTop
        val below = safeBottom - source.top
        val maxHeight = (if (hasAnchor) maxOf(above, below) else safeBottom - safeTop).coerceAtLeast(1)
        val column=LinearLayout(c).apply{
            id=R.id.native_popup_panel;orientation=LinearLayout.VERTICAL
            setPadding(c.dp(8),c.dp(9),c.dp(8),c.dp(9))
            ViewCompat.setAccessibilityPaneTitle(this,title)
        }
        val scroller=ScrollView(c).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(column)}
        lateinit var popup:MotionPopup
        items.forEachIndexed{i,item->
            val row=LinearLayout(c).apply{
                gravity=Gravity.CENTER_VERTICAL;minimumHeight=c.dp(50)
                isFocusable=true;isClickable=true
                background=StateListDrawable().apply{
                    addState(intArrayOf(android.R.attr.state_pressed),colors.background(colors.selected,c.dp(18).toFloat()))
                    addState(intArrayOf(android.R.attr.state_focused),colors.background(colors.selected,c.dp(18).toFloat()))
                    addState(intArrayOf(),colors.background(android.graphics.Color.TRANSPARENT,c.dp(18).toFloat()))
                }
                setPadding(c.dp(15),c.dp(13),c.dp(15),c.dp(13))
            }
            val titleView = c.label(item.title, 18f, colors.text).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val words = item.subtitle?.let { path ->
                LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleView, LinearLayout.LayoutParams(-1, -2))
                    addView(c.label(path, 13f, colors.text).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        alpha = .72f
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(4) })
                }
            } ?: titleView
            row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            if(i==checked)row.addView(ImageView(c).apply{setImageDrawable(Art.drawable(c,"check"));contentDescription="当前选项"},LinearLayout.LayoutParams(c.dp(21),c.dp(21)).apply{leftMargin=c.dp(10)})
            if (onRemove != null && item.removeDescription != null) {
                row.setPadding(c.dp(15), c.dp(6), c.dp(4), c.dp(6))
                row.addView(ImageButton(c).apply {
                    setImageDrawable(Art.drawable(c, "delete"))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(12))
                    accessible(item.removeDescription)
                    setOnClickListener { popup.close { onRemove(i) } }
                }, LinearLayout.LayoutParams(c.dp(48), c.dp(48)))
            }
            row.setOnClickListener{popup.close{selected(i)}}
            column.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
        scroller.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST))
        val height=minOf(scroller.measuredHeight,maxHeight)
        val right = if (hasAnchor) source.right else frame.right - c.dp(12)
        val x = (right-width).coerceIn(frame.left+c.dp(12),maxOf(frame.left+c.dp(12),frame.right-width-c.dp(12)))
        val y = when {
            !hasAnchor -> safeTop
            below >= height -> source.top
            else -> source.bottom - height
        }.coerceIn(safeTop, maxOf(safeTop, safeBottom - height))
        val origin = if (hasAnchor) RectF(source) else
            RectF((frame.right-c.dp(60)).toFloat(),(safeTop).toFloat(),(frame.right-c.dp(12)).toFloat(),(safeTop+c.dp(48)).toFloat())
        origin.offset(-frame.left.toFloat(), -frame.top.toFloat())
        val visibleAnchor = anchor?.takeIf { hasAnchor }
        val radius = visibleAnchor?.surfaceCornerRadius(source.width(), source.height(),
            minOf(source.width(), source.height()) / 2f) ?: minOf(origin.width(), origin.height()) / 2f
        val destination = RectF((x-frame.left).toFloat(),(y-frame.top).toFloat(),
            (x-frame.left+width).toFloat(),(y-frame.top+height).toFloat())
        val rows = List(column.childCount, column::getChildAt)
        popup=MotionPopup(c,scroller,frame,destination,origin,radius,visibleAnchor,rows)
        active=popup;popup.setOnDismissListener{if(active===popup)active=null}
        try{popup.show(parent)}catch(_:WindowManager.BadTokenException){popup.dispose();if(active===popup)active=null}
    }
}
