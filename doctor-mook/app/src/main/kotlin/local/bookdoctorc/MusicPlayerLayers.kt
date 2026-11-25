package local.bookdoctorc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView

/** The cover tint spans the player surface, so the lyrics never sit inside a card. */
internal class MusicPlayerBackdrop(private val ui: ShelfUi) : FrameLayout(ui.context) {
    private val artwork = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    init {
        background = MusicPage.wall(ui)
        addView(artwork, LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH))
        val wash = View(context)
        wash.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (ui.dark)
            intArrayOf(0x68101c2c, 0xad121e2e.toInt(), 0xf0142030.toInt())
        else intArrayOf(0x64f8faff, 0x9aebf1fa.toInt(), 0xe4e0eaf4.toInt()))
        addView(wash, LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH))
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = ui.dp(34f).toFloat()
            artwork.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        reveal(0f)
    }

    fun show(bitmap: Bitmap?, fallback: Int) {
        if (bitmap == null) artwork.setImageResource(fallback)
        else if (Build.VERSION.SDK_INT >= 31) artwork.setImageBitmap(bitmap)
        else {
            // A tiny, filtered preview keeps the wide wash inexpensive on older Android versions.
            val ratio = 12f / maxOf(bitmap.width, bitmap.height)
            artwork.setImageBitmap(Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1), true))
        }
    }

    fun reveal(progress: Float) {
        artwork.alpha = if (ui.dark) .22f + .54f * progress else .27f + .45f * progress
    }
}

internal class LyricViewport(
    ui: ShelfUi, private val tapped: (Float, Float) -> Unit, private val tracking: (Boolean) -> Unit
) : FrameLayout(ui.context) {
    private val fade = ui.dp(43f)
    private val destinationMask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    val scroll = object : ScrollView(context) {
        private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapped(e.x, e.y)
                return true
            }
        }).apply { setIsLongpressEnabled(false) }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) tracking(true)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                tracking(false)
            }
            val tap = gesture.onTouchEvent(event) && event.actionMasked == MotionEvent.ACTION_UP
            if (!tap) return super.dispatchTouchEvent(event)
            // Route touches by the text runs. The row click remains available to accessibility.
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Keep the tap sequence even when a status or short lyric does not scroll.
            return super.onTouchEvent(event) || event.actionMasked == MotionEvent.ACTION_DOWN
        }
    }.apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
    }

    init { addView(scroll, LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH)) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        val edge = (fade.toFloat() / h).coerceAtMost(.23f)
        destinationMask.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0, -1, -1, 0), floatArrayOf(0f, edge, 1f - edge, 1f), Shader.TileMode.CLAMP)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), destinationMask)
        canvas.restoreToCount(layer)
    }
}
