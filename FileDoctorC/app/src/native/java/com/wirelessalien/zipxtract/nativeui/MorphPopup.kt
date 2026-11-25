package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.PopupWindow
import kotlin.math.roundToInt

/** One moving outline around fixed-layout menu rows. */
class MorphSurface(
    context: Context,
    origin: RectF,
    target: RectF,
    private val body: View,
    private val originRadius: Float = minOf(origin.width(), origin.height()) / 2f,
    private val rows: List<View> = emptyList()
) : FrameLayout(context) {
    val originBounds = RectF(origin)
    val targetBounds = RectF(target)
    private val bounds = RectF(origin)
    val currentBounds: RectF get() = RectF(bounds)
    var progress = 0f
        private set
    private var radius = originRadius
    private val targetRadius = minOf(context.dp(26).toFloat(), minOf(target.width(), target.height()) / 2f)
    private val growsDown = target.centerY() >= origin.centerY()
    private val palette = Palette(context)
    private var shadowAlpha = 0f
    private val shape = GradientDrawable().apply {
        setColor(if (palette.dark) 0xff25262a.toInt() else 0xfffdfdfc.toInt())
        setStroke(context.dp(1), if (palette.dark) 0x225e6879 else 0xaaffffff.toInt())
    }

    init {
        setWillNotDraw(false)
        clipToOutline = true
        isClickable = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(shape.bounds, radius)
                outline.alpha = shadowAlpha
            }
        }
        addView(body, LayoutParams(target.width().roundToInt(), target.height().roundToInt(),
            Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = target.left.roundToInt()
            topMargin = target.top.roundToInt()
        })
    }

    override fun onDraw(canvas: Canvas) {
        shape.draw(canvas)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!bounds.contains(event.x, event.y)) return false
            if (progress < .75f) return true
        }
        return super.dispatchTouchEvent(event)
    }

    fun updateShape(value: Float, sourceReveal: Float = 0f) {
        progress = value.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * progress
        bounds.set(
            mix(originBounds.left, targetBounds.left).roundToInt().toFloat(),
            mix(originBounds.top, targetBounds.top).roundToInt().toFloat(),
            mix(originBounds.right, targetBounds.right).roundToInt().toFloat(),
            mix(originBounds.bottom, targetBounds.bottom).roundToInt().toFloat()
        )
        radius = mix(originRadius, targetRadius)
        shape.setBounds(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
        shape.cornerRadius = radius
        shape.alpha = (255 * (1f - sourceReveal)).roundToInt()
        // Outline alpha controls the contact shadow even when Android rounds a tiny Z offset.
        shadowAlpha = smoothstep(((progress - .28f) / .72f).coerceIn(0f, 1f))
        elevation = context.dp(6) * shadowAlpha
        if (rows.isEmpty()) {
            body.alpha = smoothstep(((progress - .20f) / .66f).coerceIn(0f, 1f))
        } else {
            rows.forEachIndexed { index, row ->
                val order = if (growsDown) index else rows.lastIndex - index
                val delay = minOf(order * .028f, .15f)
                val reveal = smoothstep(((progress - .24f - delay) / .55f).coerceIn(0f, 1f))
                row.alpha = reveal
                row.translationY = context.dp(7) * (1f - reveal) * if (growsDown) -1f else 1f
            }
        }
        invalidateOutline()
        invalidate()
    }

    private fun smoothstep(value: Float) = value * value * (3f - 2f * value)

}

/** One transparent window hosts the outline; the source button only owns the resting state. */
class MotionPopup(
    context: Context, body: View, private val frame: Rect, target: RectF, origin: RectF,
    originRadius: Float, private val anchor: View?, rows: List<View>
) : PopupWindow(context) {
    private val overlay = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
    }
    val motionSurface = MorphSurface(context, origin, target, body, originRadius, rows)
    private var anchorAlpha: Float? = null
    private var closing = false
    private var finished = false

    init {
        contentView = overlay
        width = frame.width()
        height = frame.height()
        isFocusable = true
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = 0f
        animationStyle = 0
        isOutsideTouchable = true
        isClippingEnabled = false
        setAttachedInDecor(false)
        if (android.os.Build.VERSION.SDK_INT >= 29) setIsLaidOutInScreen(true)
        inputMethodMode = INPUT_METHOD_NOT_NEEDED
        overlay.addView(motionSurface, FrameLayout.LayoutParams(-1, -1))
        overlay.setOnClickListener { dismiss() }
        motionSurface.updateShape(0f)
    }

    fun show(parent: View) {
        anchorAlpha = anchor?.alpha
        frame(0f)
        showAtLocation(parent, Gravity.TOP or Gravity.LEFT, frame.left, frame.top)
        UiMotion.drive(motionSurface, 0f, 1f, 360L, ::frame)
    }

    private fun frame(progress: Float) {
        val reveal = MotionFrames.sourceReveal(progress)
        motionSurface.updateShape(progress, reveal)
        anchorAlpha?.let { anchor?.alpha = it * reveal }
    }

    fun close(done: () -> Unit) {
        if (closing || finished) return
        if (!isShowing) {
            dispose()
            done()
            return
        }
        closing = true
        UiMotion.drive(motionSurface, motionSurface.progress, 0f, 260L, ::frame) {
            dispose()
            done()
        }
    }

    override fun dismiss() = close {}

    fun dispose() {
        if (finished) return
        finished = true
        UiMotion.cancel(motionSurface)
        try {
            super.dismiss()
        } finally {
            anchorAlpha?.let { alpha -> anchor?.alpha = alpha }
            anchorAlpha = null
        }
    }
}
