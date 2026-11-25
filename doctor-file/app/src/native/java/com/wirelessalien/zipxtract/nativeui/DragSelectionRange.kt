package com.wirelessalien.zipxtract.nativeui

import kotlin.math.max
import kotlin.math.min

/**
 * One immutable adapter-order snapshot for a drag-selection gesture. Null keys are headings,
 * spacers, etc. The anchor's original state determines whether this gesture selects or clears.
 * Leaving the current range restores each key's ORIGINAL state, not its last visited state.
 * There is deliberately no Android dependency so range semantics can be regression-tested on JVM.
 */
class DragSelectionRange<K : Any>(
    keys: List<K?>,
    initiallySelected: Set<K>,
    val anchorPosition: Int
) {
    private val keys = keys.toList()
    private val baseline = initiallySelected.toSet()
    // A key may occur more than once in a presentation; it remains in range until all occurrences
    // leave. Mutations are emitted once per identity, never once per adapter row.
    private val occurrences = mutableMapOf<K, Int>()
    private var endpoint: Int? = null

    init {
        require(anchorPosition in this.keys.indices) { "Anchor is outside the adapter snapshot" }
        require(this.keys[anchorPosition] != null) { "Anchor is not selectable" }
    }

    val selectionValue: Boolean = this.keys[anchorPosition] !in baseline
    val endPosition: Int? get() = endpoint

    /** Returns only keys whose displayed selection actually changed. Positions clamp at either end. */
    fun update(position: Int): Map<K, Boolean> {
        val next = position.coerceIn(keys.indices)
        if (endpoint == next) return emptyMap()
        val newStart = min(anchorPosition, next)
        val newEnd = max(anchorPosition, next)
        val previous = endpoint
        val before = linkedMapOf<K, Boolean>()
        fun adjust(start: Int, end: Int, delta: Int) {
            if (start > end) return
            for (index in start..end) {
                val key = keys[index] ?: continue
                if (key !in before) before[key] = state(key)
                val count = (occurrences[key] ?: 0) + delta
                if (count == 0) occurrences.remove(key) else occurrences[key] = count
            }
        }
        if (previous == null) {
            adjust(newStart, newEnd, 1)
        } else {
            val oldStart = min(anchorPosition, previous)
            val oldEnd = max(anchorPosition, previous)
            adjust(oldStart, min(oldEnd, newStart - 1), -1)
            adjust(max(oldStart, newEnd + 1), oldEnd, -1)
            adjust(newStart, min(newEnd, oldStart - 1), 1)
            adjust(max(newStart, oldEnd + 1), newEnd, 1)
        }
        endpoint = next
        return linkedMapOf<K, Boolean>().apply {
            for ((key, oldValue) in before) {
                val newValue = state(key)
                if (oldValue != newValue) put(key, newValue)
            }
        }
    }

    /** Includes original selections outside this adapter snapshot, if supplied by the caller. */
    fun selectedKeys(): Set<K> = baseline.toMutableSet().apply {
        for (key in occurrences.keys) if (selectionValue) add(key) else remove(key)
    }

    private fun state(key: K): Boolean = if (key in occurrences) selectionValue else key in baseline
}

/** Pure edge-speed calculation shared by the controller and JVM regression tests. */
object DragSelectionAutoScroll {
    /** px/second; a quadratic ramp avoids an abrupt jump when entering the edge zone. */
    fun velocity(
        y: Float,
        viewportTop: Float,
        viewportBottom: Float,
        edgeSize: Float,
        maxPixelsPerSecond: Float
    ): Float {
        if (!y.isFinite() || !viewportTop.isFinite() || !viewportBottom.isFinite() ||
            !edgeSize.isFinite() || !maxPixelsPerSecond.isFinite() ||
            viewportBottom <= viewportTop || edgeSize <= 0f || maxPixelsPerSecond <= 0f) return 0f
        val band = min(edgeSize, (viewportBottom - viewportTop) / 2f)
        val penetration = when {
            y < viewportTop + band -> -((viewportTop + band - y) / band).coerceIn(0f, 1f)
            y > viewportBottom - band -> ((y - viewportBottom + band) / band).coerceIn(0f, 1f)
            else -> return 0f
        }
        return maxPixelsPerSecond * penetration * kotlin.math.abs(penetration)
    }
}
