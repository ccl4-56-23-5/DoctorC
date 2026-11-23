package com.wirelessalien.zipxtract

import com.wirelessalien.zipxtract.nativeui.DragSelectionAutoScroll
import com.wirelessalien.zipxtract.nativeui.DragSelectionRange
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class DragSelectionRangeTest {
    @Test fun longPressSelectsAnchorAndExtendsInAdapterOrder() {
        val range = DragSelectionRange(listOf("a", "b", "c", "d", "e"), emptySet(), 1)
        assertTrue(range.selectionValue)
        assertNull(range.endPosition)
        assertEquals(mapOf("b" to true), range.update(1))
        assertEquals(mapOf("c" to true, "d" to true), range.update(3))
        assertEquals(setOf("b", "c", "d"), range.selectedKeys())
        assertEquals(3, range.endPosition)
    }

    @Test fun shrinkingRestoresTheExactPreGestureSelection() {
        val initial = setOf("c", "e", "outside-this-page")
        val range = DragSelectionRange(listOf("a", "b", "c", "d", "e"), initial, 1)
        range.update(4)
        // c/e were selected already; only d changes when the endpoint contracts to b.
        assertEquals(mapOf("d" to false), range.update(1))
        assertEquals(initial + "b", range.selectedKeys())
        assertEquals(mapOf("a" to true), range.update(0))
        assertEquals(mapOf("a" to false), range.update(1))
        assertEquals(initial + "b", range.selectedKeys())
    }

    @Test fun selectedAnchorClearsRangeAndShrinkingRestoresItsPreviouslySelectedRows() {
        val initial = setOf("a", "b", "d", "elsewhere")
        val range = DragSelectionRange(listOf("a", "b", "c", "d", "e"), initial, 1)
        assertFalse(range.selectionValue)
        assertEquals(mapOf("b" to false), range.update(1))
        assertEquals(mapOf("d" to false), range.update(4))
        assertEquals(setOf("a", "elsewhere"), range.selectedKeys())
        assertEquals(mapOf("d" to true), range.update(1))
        assertEquals(initial - "b", range.selectedKeys())
    }

    @Test fun crossingTheAnchorRestoresOldSideAndSelectsNewSide() {
        val range = DragSelectionRange(listOf("a", "b", "c", "d", "e", "f"), setOf("e"), 2)
        range.update(5)
        assertEquals(mapOf("d" to false, "f" to false, "a" to true, "b" to true), range.update(0))
        assertEquals(setOf("a", "b", "c", "e"), range.selectedKeys())
        assertEquals(mapOf("a" to false, "b" to false, "d" to true), range.update(4))
        assertEquals(setOf("c", "d", "e"), range.selectedKeys())
    }

    @Test fun headingsAndSpacersAreNotSelectionIdentities() {
        val range = DragSelectionRange(listOf(null, "a", null, "b", "c", null), emptySet(), 1)
        range.update(4)
        assertEquals(setOf("a", "b", "c"), range.selectedKeys())
        assertEquals(mapOf("b" to false, "c" to false), range.update(2))
        assertEquals(setOf("a"), range.selectedKeys())
        assertTrue(range.update(0).isEmpty())
    }

    @Test fun aGridUsesLinearAdapterRangesAcrossRowsAndDateHeadings() {
        val keys = listOf(null, "r0c0", "r0c1", "r0c2", "r0c3", null,
            "r1c0", "r1c1", "r1c2", "r1c3")
        val range = DragSelectionRange(keys, emptySet(), 3)
        range.update(8)
        assertEquals(setOf("r0c2", "r0c3", "r1c0", "r1c1", "r1c2"), range.selectedKeys())
        range.update(4)
        assertEquals(setOf("r0c2", "r0c3"), range.selectedKeys())
    }

    @Test fun duplicateIdentitiesRemainSelectedWhileAnyOccurrenceIsInRange() {
        val range = DragSelectionRange(listOf("a", "same", "b", "same", "c"), emptySet(), 1)
        range.update(4)
        assertEquals(mapOf("b" to false, "c" to false), range.update(1))
        assertEquals(setOf("same"), range.selectedKeys())
        assertTrue(range.update(1).isEmpty())
    }

    @Test fun duplicateIdentitiesRestoreOnlyAfterLastInRangeOccurrenceLeaves() {
        val range = DragSelectionRange(listOf("a", "same", "b", "same", "c"), setOf("same"), 0)
        range.update(4)
        range.update(2)
        assertEquals(setOf("a", "same", "b"), range.selectedKeys())
        range.update(0)
        assertEquals(setOf("a", "same"), range.selectedKeys())
        val clear = DragSelectionRange(listOf("a", "same", "b", "same", "c"), setOf("a", "same"), 0)
        clear.update(4)
        assertTrue(clear.update(2).isEmpty())
        assertEquals(mapOf("same" to true), clear.update(0))
        assertEquals(setOf("same"), clear.selectedKeys())
    }

    @Test fun endpointsClampAtBothSnapshotEdges() {
        val range = DragSelectionRange(listOf("a", "b", "c"), emptySet(), 1)
        range.update(Int.MAX_VALUE)
        assertEquals(2, range.endPosition)
        assertEquals(setOf("b", "c"), range.selectedKeys())
        range.update(Int.MIN_VALUE)
        assertEquals(0, range.endPosition)
        assertEquals(setOf("a", "b"), range.selectedKeys())
    }

    @Test fun snapshotDoesNotAliasMutableHostCollections() {
        val keys = mutableListOf<String?>("a", "b", "c")
        val selected = mutableSetOf("c")
        val range = DragSelectionRange(keys, selected, 0)
        keys.clear(); selected.clear()
        range.update(1)
        assertEquals(setOf("a", "b", "c"), range.selectedKeys())
        val returned = range.selectedKeys() as MutableSet<String>
        returned.clear()
        assertEquals(setOf("a", "b", "c"), range.selectedKeys())
    }

    @Test fun repeatedUpdatesEmitNoChangesAndCanBeAppliedAsAnExactDelta() {
        val selected = mutableSetOf("b", "z")
        val range = DragSelectionRange(listOf("a", "b", "c", "d"), selected, 2)
        for (endpoint in listOf(2, 3, 1, 0, 2, 2, 3, 1)) {
            for ((key, value) in range.update(endpoint)) {
                assertNotEquals("A delta must not repeat the currently displayed value", key in selected, value)
                if (value) selected.add(key) else selected.remove(key)
            }
            assertEquals(range.selectedKeys(), selected)
            assertTrue(range.update(endpoint).isEmpty())
        }
    }

    @Test fun randomizedDraggingMatchesBaselinePlusCurrentRangeIncludingDuplicatesAndNulls() {
        val random = Random(108L)
        repeat(150) {
            val keys = List(1 + random.nextInt(60)) { index ->
                if (index > 0 && random.nextInt(5) == 0) null else "key-${random.nextInt(24)}"
            }
            val anchor = keys.indices.filter { keys[it] != null }.let { it[random.nextInt(it.size)] }
            val initial = keys.filterNotNull().filter { random.nextBoolean() }.toSet() + "outside"
            val displayed = initial.toMutableSet()
            val range = DragSelectionRange(keys, initial, anchor)
            repeat(120) {
                val requested = random.nextInt(keys.size + 20) - 10
                val endpoint = requested.coerceIn(keys.indices)
                val active = keys.subList(minOf(anchor, endpoint), maxOf(anchor, endpoint) + 1)
                    .filterNotNull().toSet()
                val expected = if (keys[anchor] !in initial) initial + active else initial - active
                for ((key, value) in range.update(requested)) {
                    assertNotEquals(key in displayed, value)
                    if (value) displayed.add(key) else displayed.remove(key)
                }
                assertEquals(expected, range.selectedKeys())
                assertEquals(expected, displayed)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptySnapshotCannotStartGesture() { DragSelectionRange<String>(emptyList(), emptySet(), 0) }

    @Test(expected = IllegalArgumentException::class)
    fun outOfBoundsAnchorCannotStartGesture() { DragSelectionRange(listOf("a"), emptySet(), -1) }

    @Test(expected = IllegalArgumentException::class)
    fun nonselectableAnchorCannotStartGesture() { DragSelectionRange(listOf(null, "a"), emptySet(), 0) }

    @Test fun edgeScrollIsZeroInMiddleAndRampsContinuouslyTowardEachEdge() {
        fun speed(y: Float) = DragSelectionAutoScroll.velocity(y, 20f, 420f, 60f, 900f)
        assertEquals(0f, speed(80f), 0f)
        assertEquals(0f, speed(220f), 0f)
        assertEquals(0f, speed(360f), 0f)
        assertEquals(-225f, speed(50f), 0.001f)
        assertEquals(225f, speed(390f), 0.001f)
        assertEquals(-900f, speed(20f), 0f)
        assertEquals(900f, speed(420f), 0f)
        assertEquals(-900f, speed(-1000f), 0f)
        assertEquals(900f, speed(1000f), 0f)
        assertTrue(speed(79f) < 0f && speed(79f) > -1f)
    }

    @Test fun smallViewportDoesNotHaveOverlappingOppositeEdgeZones() {
        assertEquals(0f, DragSelectionAutoScroll.velocity(50f, 0f, 100f, 80f, 1000f), 0f)
        assertEquals(-250f, DragSelectionAutoScroll.velocity(25f, 0f, 100f, 80f, 1000f), 0f)
        assertEquals(250f, DragSelectionAutoScroll.velocity(75f, 0f, 100f, 80f, 1000f), 0f)
    }

    @Test fun invalidViewportOrConfigurationNeverRequestsScroll() {
        assertEquals(0f, DragSelectionAutoScroll.velocity(10f, 20f, 20f, 60f, 900f), 0f)
        assertEquals(0f, DragSelectionAutoScroll.velocity(10f, 20f, 0f, 60f, 900f), 0f)
        assertEquals(0f, DragSelectionAutoScroll.velocity(10f, 20f, 400f, 0f, 900f), 0f)
        assertEquals(0f, DragSelectionAutoScroll.velocity(10f, 20f, 400f, 60f, -1f), 0f)
        assertEquals(0f, DragSelectionAutoScroll.velocity(Float.NaN, 20f, 400f, 60f, 900f), 0f)
        assertEquals(0f, DragSelectionAutoScroll.velocity(10f, 20f, 400f, Float.POSITIVE_INFINITY, 900f), 0f)
    }
}
