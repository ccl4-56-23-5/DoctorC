package com.wirelessalien.zipxtract.nativeui

import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

/**
 * Passive until an existing row's long-click callback calls [beginSelection] BEFORE toggling it.
 * Works with linear and grid layouts, including nonselectable full-span headings.
 *
 * Host integration:
 * - Return a stable, namespaced identity from Host.selectionKey; null for nonselectable rows.
 * - Apply the whole delta in Host.onSelectionChanges, then refresh selection chrome once. Ordinary
 *   synchronous notifyDataSetChanged calls made by this callback do not terminate the gesture.
 * - Cancel BEFORE changing route, filtering, sorting, collapsing groups or replacing adapter data.
 *   Adapter notifications outside our selection callback also cancel as a defensive fallback.
 * - Call cancelGesture in onPause and dispose in onDestroy. Cancellation keeps selections already
 *   displayed; it never rolls back a completed drag, changes a new page or synthesizes a click.
 *
 * No second long-press recognizer is installed: the row retains its original click, accessibility,
 * long-press timeout and haptic behavior. The RecyclerView receives subsequent MOVE/UP even if the
 * selection refresh replaces that original row view.
 */
class DragSelectionController(
    private val recycler: RecyclerView,
    private val host: Host,
    edgeSizeDp: Float = 64f,
    maxScrollDpPerSecond: Float = 900f
) : RecyclerView.OnItemTouchListener, View.OnAttachStateChangeListener {
    interface Host {
        fun selectionKey(position: Int): String?
        fun isSelected(key: String): Boolean
        /** Main-thread batch. Only selection/rebinding may change here, never adapter order/data. */
        fun onSelectionChanges(changes: Map<String, Boolean>)
    }

    /** Overlay occlusion, measured inward from the corresponding RecyclerView edge, in pixels. */
    var topInsetPx: Int = 0
    var bottomInsetPx: Int = 0
    val isSelecting: Boolean get() = range != null

    private val density = recycler.resources.displayMetrics.density
    private val edgeSize = edgeSizeDp * density
    private val maxSpeed = maxScrollDpPerSecond * density
    private var disposed = false
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var pointerDown = false
    private var mayBegin = false
    private var downPosition = RecyclerView.NO_POSITION
    private var lastX = 0f
    private var lastY = 0f
    // Keep consuming a cancelled active stream until its terminal event. A second finger must not
    // turn the remainder into a row click or an ordinary scroll on a freshly opened page.
    private var consumingStream = false
    private var range: DragSelectionRange<String>? = null
    private var snapshotKeys: List<String?> = emptyList()
    private var dispatchingChanges = false
    private var observedAdapter: RecyclerView.Adapter<*>? = null
    private var frameScheduled = false
    private var lastFrameNanos = 0L
    private var scrollRemainder = 0f
    private var endpointPending = false
    private var endpointScheduled = false
    private var endpointX = 0f
    private var endpointY = 0f
    private var endpointAttempts = 0
    private var finishing = false
    private var finishDeadlineNanos = 0L

    // The first intercepted MOVE can become only CANCEL at the old child. Resolve its saved
    // coordinates AFTER dispatch. A selection rebind can temporarily give every child NO_POSITION;
    // retry independently of edge scrolling, including after UP, without retaining a MotionEvent.
    private val resolveEndpoint = Runnable {
        endpointScheduled = false
        if (endpointPending && range != null) {
            if (!validSession() || !recycler.isAttachedToWindow || !recycler.isShown) {
                cancelGesture()
            } else if (applyPendingEndpoint()) {
                if (finishing) cancelGesture() else scheduleScroll()
            } else if (range != null) {
                // A permanently suppressed/broken layout must not keep a released gesture alive.
                if (finishing && System.nanoTime() >= finishDeadlineNanos) cancelGesture()
                else scheduleEndpoint()
            }
        }
    }

    private val scrollObserver = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                // Let the actual RecyclerView/View recognizers decide slop, not a competing radial
                // distance gate. Once real scrolling wins this DOWN, returning to IDLE cannot rearm it.
                mayBegin = false
                if (range != null) cancelGesture()
            }
        }
    }

    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        private fun changed() { if (!dispatchingChanges) cancelGesture() }
        override fun onChanged() = changed()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = changed()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = changed()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = changed()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = changed()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = changed()
    }

    private val scrollFrame = Runnable {
        frameScheduled = false
        val active = range
        if (active != null && !finishing && pointerDown) {
            if (!validSession() || !recycler.isAttachedToWindow || !recycler.isShown) {
                cancelGesture()
            } else {
                val velocity = edgeVelocity()
                val direction = if (velocity < 0f) -1 else 1
                if (velocity != 0f && recycler.canScrollVertically(direction)) {
                    val now = System.nanoTime()
                    val seconds = if (lastFrameNanos == 0L) 1f / 60f
                        else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    lastFrameNanos = now
                    scrollRemainder += velocity * seconds
                    val delta = scrollRemainder.toInt()
                    scrollRemainder -= delta
                    if (delta != 0 && !recycler.isComputingLayout) recycler.scrollBy(0, delta)
                    // Re-hit-test after scroll/layout: a motionless finger at the edge must keep
                    // extending the range as previously offscreen items enter the viewport.
                    requestEndpoint(afterDispatch = false)
                    if (range === active) scheduleScroll()
                } else {
                    lastFrameNanos = 0L
                    scrollRemainder = 0f
                    requestEndpoint(afterDispatch = false)
                }
            }
        }
    }

    init {
        require(edgeSizeDp.isFinite() && edgeSizeDp > 0f)
        require(maxScrollDpPerSecond.isFinite() && maxScrollDpPerSecond > 0f)
        recycler.addOnItemTouchListener(this)
        recycler.addOnScrollListener(scrollObserver)
        recycler.addOnAttachStateChangeListener(this)
        observeAdapter()
    }

    /**
     * Call from the row's existing long-click BEFORE its old toggle/refresh action. True means the
     * controller handled both the initial toggle and the drag. False means no live eligible finger
     * (e.g. accessibility performLongClick); preserve the original single-item long-click behavior.
     */
    fun beginSelection(position: Int): Boolean {
        if (disposed || !pointerDown || !mayBegin || consumingStream ||
            position != downPosition || recycler.scrollState == RecyclerView.SCROLL_STATE_DRAGGING ||
            recycler.isComputingLayout) return false
        val adapter = recycler.adapter ?: return false
        if (adapter !== observedAdapter) {
            observeAdapter()
            return false // A different adapter arrived after DOWN; that DOWN is no longer eligible.
        }
        if (position !in 0 until adapter.itemCount || host.selectionKey(position) == null) return false
        val keys = List(adapter.itemCount) { host.selectionKey(it) }
        val anchor = keys.getOrNull(position) ?: return false
        val selected = keys.filterNotNull().toSet().filterTo(linkedSetOf()) { host.isSelected(it) }
        // Data must stay stable throughout snapshot collection, not just at the hit-tested anchor.
        if (recycler.adapter !== adapter || adapter.itemCount != keys.size ||
            host.selectionKey(position) != anchor) return false
        val next = DragSelectionRange(keys, selected, position)
        snapshotKeys = keys
        range = next
        mayBegin = false
        consumingStream = true
        recycler.stopScroll()
        recycler.parent?.requestDisallowInterceptTouchEvent(true)
        dispatch(next.update(position))
        if (range === next) scheduleScroll()
        return true
    }

    /** Ends range updates and edge scrolling, preserving the selection that the user last saw. */
    fun cancelGesture() {
        val wasSelecting = range != null
        range = null
        snapshotKeys = emptyList()
        mayBegin = false
        recycler.removeCallbacks(scrollFrame)
        recycler.removeCallbacks(resolveEndpoint)
        endpointPending = false
        endpointScheduled = false
        endpointAttempts = 0
        finishing = false
        finishDeadlineNanos = 0L
        frameScheduled = false
        lastFrameNanos = 0L
        scrollRemainder = 0f
        if (wasSelecting) recycler.parent?.requestDisallowInterceptTouchEvent(false)
    }

    fun dispose() {
        if (disposed) return
        cancelGesture()
        disposed = true
        observedAdapter?.unregisterAdapterDataObserver(dataObserver)
        observedAdapter = null
        recycler.removeOnItemTouchListener(this)
        recycler.removeOnAttachStateChangeListener(this)
        clearPointer()
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
        if (disposed) return false
        val consume = consumingStream && event.actionMasked != MotionEvent.ACTION_DOWN
        handle(event, deferMoveUntilIntercepted = consume)
        return consume || consumingStream
    }

    override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
        if (!disposed) handle(event)
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    override fun onViewAttachedToWindow(view: View) { if (!disposed) observeAdapter() }
    override fun onViewDetachedFromWindow(view: View) {
        cancelGesture()
        clearPointer()
    }

    private fun handle(event: MotionEvent, deferMoveUntilIntercepted: Boolean = false) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelGesture()
                clearPointer()
                observeAdapter()
                pointerDown = event.pointerCount == 1
                mayBegin = pointerDown
                pointerId = if (pointerDown) event.getPointerId(0) else MotionEvent.INVALID_POINTER_ID
                lastX = event.x; lastY = event.y
                downPosition = recycler.findChildViewUnder(lastX, lastY)?.let {
                    recycler.getChildAdapterPosition(it)
                } ?: RecyclerView.NO_POSITION
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Never switch the selecting finger to a different pointer ID.
                cancelGesture()
                mayBegin = false
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (!pointerDown || event.pointerCount != 1 || index < 0) {
                    cancelGesture()
                    return
                }
                lastX = event.getX(index); lastY = event.getY(index)
                if (range == null) {
                    // The row's own long-click remains the only recognizer. A competing radial
                    // touch-slop veto here disabled drag selection whenever the finger drifted
                    // sideways inside a wide row or diagonally, while the row still reported a
                    // long press and RecyclerView did not scroll: beginSelection() then refused and
                    // the host fell back to a one-item toggle, so later MOVE events had no range.
                    // Real scrolling still vetoes this DOWN through the scroll-state observer.
                } else if (deferMoveUntilIntercepted) {
                    requestEndpoint(afterDispatch = true)
                } else {
                    requestEndpoint(afterDispatch = false)
                    scheduleScroll()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (range != null && event.actionMasked == MotionEvent.ACTION_UP) {
                    // Flush the last MOVE (never the UP coordinates) through the same layout-aware
                    // retry, so a single cross-row MOVE followed immediately by UP is not lost.
                    finishing = true
                    finishDeadlineNanos = System.nanoTime() + FINISH_TIMEOUT_NANOS
                    requestEndpoint(afterDispatch = false)
                    if (range != null && endpointPending) {
                        clearPointer()
                        return
                    }
                }
                cancelGesture()
                clearPointer()
            }
        }
    }

    private fun clearPointer() {
        pointerId = MotionEvent.INVALID_POINTER_ID
        pointerDown = false
        mayBegin = false
        downPosition = RecyclerView.NO_POSITION
        consumingStream = false
    }

    private fun observeAdapter() {
        val adapter = recycler.adapter
        if (observedAdapter === adapter) return
        cancelGesture()
        observedAdapter?.unregisterAdapterDataObserver(dataObserver)
        observedAdapter = adapter
        adapter?.registerAdapterDataObserver(dataObserver)
    }

    private fun validSession(): Boolean {
        val active = range ?: return false
        if (recycler.adapter !== observedAdapter || observedAdapter?.itemCount != snapshotKeys.size ||
            host.selectionKey(active.anchorPosition) != snapshotKeys[active.anchorPosition]) {
            cancelGesture()
            return false
        }
        return true
    }

    private fun updateAtPointer() {
        val active = range ?: return
        if (!validSession() || recycler.isComputingLayout) return
        val position = positionAt(lastX, lastY) ?: return
        if (host.selectionKey(position) != snapshotKeys.getOrNull(position)) {
            cancelGesture()
            return
        }
        dispatch(active.update(position))
    }

    private fun dispatch(changes: Map<String, Boolean>) {
        if (changes.isEmpty()) return
        dispatchingChanges = true
        try { host.onSelectionChanges(changes) } finally { dispatchingChanges = false }
    }

    /**
     * Records the newest pointer coordinates and resolves them once a usable layout exists. The
     * coordinates are retained rather than the MotionEvent; adapter updates (including a full
     * notifyDataSetChanged from an older host) can leave every child at NO_POSITION until the next
     * layout pass, and a single failed post used to drop the endpoint permanently.
     */
    private fun requestEndpoint(afterDispatch: Boolean) {
        if (range == null) return
        endpointX = lastX
        endpointY = lastY
        endpointPending = true
        endpointAttempts = 0
        if (afterDispatch) recycler.post(resolveEndpoint) else scheduleEndpoint()
    }

    private fun scheduleEndpoint() {
        if (range == null || !endpointPending || endpointScheduled) return
        if (++endpointAttempts > MAX_ENDPOINT_ATTEMPTS) {
            // Never spin forever on a suppressed or permanently broken layout.
            cancelGesture()
            return
        }
        endpointScheduled = true
        recycler.postOnAnimation(resolveEndpoint)
    }

    /** True when the pending coordinates were consumed (applied, or deliberately invalidated). */
    private fun applyPendingEndpoint(): Boolean {
        if (!endpointPending) return true
        if (range == null) return false
        if (recycler.isComputingLayout) return false
        // Positions of current children are not meaningful while an adapter notification is
        // waiting for layout; retry on a later frame instead of dropping this endpoint.
        if (recycler.hasPendingAdapterUpdates()) return false
        val active = range ?: return false
        val position = positionAt(endpointX, endpointY) ?: return false
        if (host.selectionKey(position) != snapshotKeys.getOrNull(position)) {
            cancelGesture()
            return true
        }
        endpointPending = false
        dispatch(active.update(position))
        return true
    }

    private fun viewportTop() = max(recycler.paddingTop, topInsetPx.coerceAtLeast(0)).toFloat()
    private fun viewportBottom() = min(recycler.height - recycler.paddingBottom,
        recycler.height - bottomInsetPx.coerceAtLeast(0)).toFloat()

    private fun edgeVelocity() = DragSelectionAutoScroll.velocity(
        lastY, viewportTop(), viewportBottom(), edgeSize, maxSpeed)

    private fun scheduleScroll() {
        if (range == null || frameScheduled) return
        val velocity = edgeVelocity()
        if (velocity == 0f) {
            lastFrameNanos = 0L
            scrollRemainder = 0f
            return
        }
        val direction = if (velocity < 0f) -1 else 1
        if (!recycler.canScrollVertically(direction)) return
        frameScheduled = true
        recycler.postOnAnimation(scrollFrame)
    }

    /**
     * A direct hit is exact for both layouts. Gaps, full-span headings, empty last grid columns and
     * pointers beyond an edge resolve to the nearest visible selectable cell, not NO_POSITION.
     */
    private fun positionAt(x: Float, y: Float): Int? {
        val top = viewportTop()
        val bottom = viewportBottom()
        if (recycler.width <= 0 || bottom <= top) return null
        val px = x.coerceIn(0f, (recycler.width - 1).toFloat())
        val py = y.coerceIn(top, max(top, bottom - 1f))
        recycler.findChildViewUnder(px, py)?.let { child ->
            val position = recycler.getChildAdapterPosition(child)
            if (snapshotKeys.getOrNull(position) != null) return position
        }
        var nearest: Int? = null
        var nearestDistance = Float.POSITIVE_INFINITY
        for (index in 0 until recycler.childCount) {
            val child = recycler.getChildAt(index)
            if (child.bottom + child.translationY <= top || child.top + child.translationY >= bottom) continue
            val position = recycler.getChildAdapterPosition(child)
            if (snapshotKeys.getOrNull(position) == null) continue
            val left = child.left + child.translationX
            val right = child.right + child.translationX
            val childTop = child.top + child.translationY
            val childBottom = child.bottom + child.translationY
            val dx = max(max(left - px, 0f), px - right)
            val dy = max(max(childTop - py, 0f), py - childBottom)
            val distance = dx * dx + dy * dy
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = position
            }
        }
        return nearest
    }

    private companion object {
        /** ~3 seconds of frames: a layout that never becomes usable must not leak a gesture. */
        const val MAX_ENDPOINT_ATTEMPTS = 180
        const val FINISH_TIMEOUT_NANOS = 250_000_000L
    }
}
