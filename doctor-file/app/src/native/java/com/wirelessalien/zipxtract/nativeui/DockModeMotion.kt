package com.wirelessalien.zipxtract.nativeui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** The live capsule and search glass move. Only outgoing icons and labels are sampled once. */
internal class DockModeMotion(
    private val host: FrameLayout,
    private val capsule: View,
    private val search: View,
    private val content: View
) {
    private data class Visual(val capsule: RectF, val search: RectF?, val searchAlpha: Float)
    private data class Origin(val visual: Visual, val foreground: Bitmap)
    private data class Change(val start: Origin, val end: Visual)

    private var pending: Origin? = null
    private var change: Change? = null
    private var progress = 0f
    private var restingClip: Boolean? = null
    val active: Boolean get() = pending != null || change != null

    private val searchIcon = (search as? ViewGroup)?.getChildAt(0)
    private val clip = Path()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outgoing = object : View(host.context) {
        override fun onDraw(canvas: Canvas) {
            val bitmap = change?.start?.foreground ?: return
            val fade = (1f - progress / .54f).coerceIn(0f, 1f)
            if (fade <= 0f) return
            val save = canvas.save()
            clipToCapsule(canvas)
            paint.alpha = (255 * fade).roundToInt()
            canvas.drawBitmap(bitmap, 0f, (host.height - bitmap.height).toFloat(), paint)
            canvas.restoreToCount(save)
        }
    }

    init {
        outgoing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // The capsule and search draw with elevation. Keep sampled foreground above their glass.
        outgoing.translationZ = host.context.dp(12).toFloat()
        host.addView(outgoing, FrameLayout.LayoutParams(-1, -1))
    }

    fun capture() {
        if (!host.isLaidOut || host.width <= 0 || host.height <= 0 || capsule.width <= 0 || content.width <= 0) return
        val visual = Visual(bounds(capsule), search.takeIf { it.visibility == View.VISIBLE && it.alpha > 0f }
            ?.let(::bounds), search.alpha)
        val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        outgoing.draw(canvas)
        val x = (capsule.left + content.left).toFloat()
        val y = (capsule.top + content.top).toFloat()
        val save = canvas.save()
        clipToCapsule(canvas)
        canvas.saveLayerAlpha(x, y, x + content.width, y + content.height, (255 * content.alpha).roundToInt())
        canvas.translate(x, y)
        content.draw(canvas)
        canvas.restoreToCount(save)

        UiMotion.cancel(host)
        change = null
        pending = Origin(visual, bitmap)
    }

    fun play(destination: DockGeometry.Layout) {
        val origin = pending ?: return
        pending = null
        val end = Visual(destination.capsule.fromBottom(destination.hostHeight),
            destination.search?.fromBottom(destination.hostHeight), if (destination.search == null) 0f else 1f)
        change = Change(origin, end)
        if (restingClip == null) restingClip = capsule.clipToOutline
        capsule.clipToOutline = true
        progress = 0f
        UiMotion.drive(host, 0f, 1f, UiMotion.ENTER, { value ->
            progress = value
            applyFrame()
        }, ::finish)
    }

    /** FrameLayout can relayout the resting positions after an insets or content change. */
    fun applyFrame() {
        val transition = change ?: return
        val start = transition.start.visual
        val end = transition.end
        val shell = interpolate(start.capsule, end.capsule, progress)
        place(capsule, shell)
        val circle = interpolate(start.search ?: collapsedAt(start.capsule),
            end.search ?: collapsedAt(end.capsule), progress)
        val startAlpha = if (start.search == null) 0f else start.searchAlpha
        search.alpha = startAlpha + (end.searchAlpha - startAlpha) * progress
        search.visibility = if (search.alpha > .02f) View.VISIBLE else View.INVISIBLE
        place(search, circle)
        val fullCircleHeight = maxOf(start.search?.height() ?: 0f, end.search?.height() ?: 0f, 1f)
        searchIcon?.alpha = ((circle.height() / fullCircleHeight - .5f) * 2f).coerceIn(0f, 1f)
        content.alpha = ((progress - .38f) / .62f).coerceIn(0f, 1f)
        outgoing.invalidate()
    }

    fun finish() {
        UiMotion.cancel(host)
        val end = change?.end
        pending = null
        change = null
        restingClip?.let { capsule.clipToOutline = it }
        restingClip = null
        content.alpha = 1f
        search.alpha = 1f
        searchIcon?.alpha = 1f
        if (end != null) {
            place(capsule, end.capsule)
            search.visibility = if (end.search == null) View.GONE else View.VISIBLE
            end.search?.let { place(search, it) }
        }
        outgoing.invalidate()
    }

    private fun clipToCapsule(canvas: Canvas) {
        val radius = minOf(capsule.width, capsule.height) / 2f
        clip.rewind()
        clip.addRoundRect(capsule.left.toFloat(), capsule.top.toFloat(), capsule.right.toFloat(),
            capsule.bottom.toFloat(), radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
    }

    private fun bounds(view: View) = RectF(view.left.toFloat(), (view.top - host.height).toFloat(),
        view.right.toFloat(), (view.bottom - host.height).toFloat())

    private fun DockGeometry.Box.fromBottom(height: Int) =
        RectF(left.toFloat(), (top - height).toFloat(), right.toFloat(), (bottom - height).toFloat())

    private fun collapsedAt(shell: RectF): RectF {
        val x = shell.right - shell.height() * .46f
        val y = shell.centerY()
        return RectF(x, y, x + 1f, y + 1f)
    }

    private fun interpolate(start: RectF, end: RectF, progress: Float): RectF {
        fun mix(a: Float, b: Float) = a + (b - a) * progress
        return RectF(mix(start.left, end.left), mix(start.top, end.top),
            mix(start.right, end.right), mix(start.bottom, end.bottom))
    }

    private fun place(view: View, bounds: RectF) {
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt() + host.height
        view.layout(left, top, bounds.right.roundToInt().coerceAtLeast(left + 1),
            (bounds.bottom.roundToInt() + host.height).coerceAtLeast(top + 1))
    }
}