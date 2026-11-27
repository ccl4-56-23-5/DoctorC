package com.wirelessalien.zipxtract.activity

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.doOnLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.wirelessalien.zipxtract.nativeui.dp
import com.wirelessalien.zipxtract.nativeui.label
import kotlin.math.min
import kotlin.math.roundToInt

internal class PreviewPhotoPage(context: Context) : FrameLayout(context) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val unavailable = context.label("无法预览这张图片", 15f, Color.WHITE).apply { visibility = GONE }
    private var requests: RequestManager? = null
    private var generation = 0
    private var multiTouch = false
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom((image.scaleX * detector.scaleFactor).coerceIn(1f, 5f), detector.focusX, detector.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = false }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            zoom(if (image.scaleX > 1f) 1f else 2.5f, event.x, event.y)
            return true
        }

        override fun onScroll(start: MotionEvent?, current: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!multiTouch && image.scaleX > 1f) {
                transform(image.scaleX, image.translationX - dx, image.translationY - dy)
            }
            return true
        }
    })

    init {
        addView(image, LayoutParams(-1, -1))
        addView(unavailable, LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun bind(uri: Uri?, name: String) {
        clear()
        val request = generation
        image.contentDescription = name
        unavailable.visibility = if (uri == null) VISIBLE else GONE
        if (uri == null) return
        doOnLayout {
            if (request != generation) return@doOnLayout
            // The existing Glide decoder is bounded to twice the page size.
            val manager = Glide.with(image).also { requests = it }
            manager.load(uri).override((width * 2).coerceIn(1, 4096), (height * 2).coerceIn(1, 4096))
                .fitCenter()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        error: GlideException?, model: Any?,
                        target: Target<Drawable>,
                        first: Boolean
                    ): Boolean {
                        if (request == generation) unavailable.visibility = VISIBLE
                        return false
                    }
                    override fun onResourceReady(
                        resource: Drawable, model: Any,
                        target: Target<Drawable>?,
                        source: DataSource, first: Boolean
                    ) = false
                }).into(image)
        }
    }

    fun clear() {
        generation++
        requests?.clear(image)
        image.scaleX = 1f
        image.scaleY = 1f
        image.translationX = 0f
        image.translationY = 0f
        multiTouch = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                if (image.scaleX > 1f) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        pinch.onTouchEvent(event)
        if (!multiTouch || event.actionMasked == MotionEvent.ACTION_CANCEL) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun zoom(scale: Float, x: Float, y: Float) {
        val factor = scale / image.scaleX
        transform(scale, (image.translationX + width / 2f - x) * factor + x - width / 2f,
            (image.translationY + height / 2f - y) * factor + y - height / 2f)
    }

    private fun transform(scale: Float, left: Float, top: Float) {
        val drawable = image.drawable ?: return
        val sourceWidth = drawable.intrinsicWidth.coerceAtLeast(1)
        val sourceHeight = drawable.intrinsicHeight.coerceAtLeast(1)
        val ratio = min(width / sourceWidth.toFloat(), height / sourceHeight.toFloat())
        val limitX = ((sourceWidth * ratio * scale - width) / 2f).coerceAtLeast(0f)
        val limitY = ((sourceHeight * ratio * scale - height) / 2f).coerceAtLeast(0f)
        image.scaleX = scale
        image.scaleY = scale
        image.translationX = left.coerceIn(-limitX, limitX)
        image.translationY = top.coerceIn(-limitY, limitY)
    }
}

internal class PreviewVideoPage(context: Context) : FrameLayout(context) {
    val surface = SurfaceView(context).apply {
        // SurfaceView keeps the platform HDR composition path.
        if (Build.VERSION.SDK_INT >= 34) setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
    }
    private val message = context.label("", 15f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        setPadding(context.dp(16), 0, context.dp(16), 0)
        visibility = GONE
    }
    private var aspect = 0f

    init {
        addView(surface, LayoutParams(-1, -1, Gravity.CENTER))
        addView(message, LayoutParams(-1, -2, Gravity.CENTER))
    }

    fun report(text: String?) {
        message.text = text.orEmpty()
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun aspect(value: Float) {
        aspect = value.takeIf { it.isFinite() && it > 0f } ?: 0f
        resizeSurface()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeSurface()
    }

    private fun resizeSurface() {
        if (width <= 0 || height <= 0) return
        val targetWidth: Int
        val targetHeight: Int
        if (aspect == 0f) {
            targetWidth = width
            targetHeight = height
        } else {
            targetWidth = min(width, (height * aspect).roundToInt().coerceAtLeast(1))
            targetHeight = min(height, (width / aspect).roundToInt().coerceAtLeast(1))
        }
        val params = surface.layoutParams
        if (params.width == targetWidth && params.height == targetHeight) return
        params.width = targetWidth
        params.height = targetHeight
        surface.layoutParams = params
    }
}
