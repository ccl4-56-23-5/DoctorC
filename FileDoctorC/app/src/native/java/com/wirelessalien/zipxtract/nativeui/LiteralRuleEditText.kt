package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/** Show spaces without replacing characters or touching IME composing spans. */
@android.annotation.SuppressLint("AppCompatCustomView") // NativeTheme uses platform EditText styling.
class LiteralRuleEditText(context: Context) : EditText(context) {
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        isFocusable = true; isFocusableInTouchMode = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lines = layout ?: return
        val value = text ?: return
        marker.color = currentTextColor; marker.alpha = 110
        val saved = canvas.save()
        canvas.clipRect(scrollX + compoundPaddingLeft, scrollY + extendedPaddingTop,
            scrollX + width - compoundPaddingRight, scrollY + height - extendedPaddingBottom)
        val first = lines.getLineForVertical(scrollY)
        val last = lines.getLineForVertical(scrollY + height)
        for (line in first..last) {
            val end = minOf(lines.getLineEnd(line), value.length)
            for (index in lines.getLineStart(line) until end) if (value[index] == ' ') {
                val left = lines.getPrimaryHorizontal(index)
                val right = if (index + 1 < end) lines.getPrimaryHorizontal(index + 1) else left + paint.measureText(" ")
                canvas.drawSpaceGuide(compoundPaddingLeft + left, compoundPaddingLeft + right,
                    (totalPaddingTop + lines.getLineBaseline(line)).toFloat(), paint.textSize, marker)
            }
        }
        canvas.restoreToCount(saved)
    }
}

/** Draw a small open-top guide in the space's advance, without painting a character. */
internal fun Canvas.drawSpaceGuide(left: Float, right: Float, baseline: Float, textSize: Float, paint: Paint) {
    val width = kotlin.math.abs(right - left)
    if (width < 2f) return
    val inset = width * .16f
    val start = minOf(left, right) + inset
    val end = maxOf(left, right) - inset
    val bottom = baseline + textSize * .06f
    val top = bottom - textSize * .13f
    paint.strokeWidth = (textSize * .045f).coerceAtLeast(1f)
    drawLine(start, top, start, bottom, paint)
    drawLine(start, bottom, end, bottom, paint)
    drawLine(end, bottom, end, top, paint)
}
