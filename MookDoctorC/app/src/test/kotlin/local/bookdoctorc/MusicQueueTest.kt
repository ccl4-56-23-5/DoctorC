package local.bookdoctorc

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class MusicQueueTest {
    private val lists = listOf(listOf("a", "b"), listOf("c", "d"))
    private val queue = MusicQueue(Random(14))
    private val history = { key: String -> if (key == "c") 25 else 0 }

    @Test fun sequentialCrossesPlaylistAndStopsAtTheEnd() {
        assertEquals("c", queue.next(lists, "b", MusicQueue.Mode.SEQUENCE, history, true))
        assertNull(queue.next(lists, "d", MusicQueue.Mode.SEQUENCE, history, true))
        assertEquals("a", queue.next(lists, "b", MusicQueue.Mode.LOOP, history, true))
    }

    @Test fun shuffleNeverDrawsAnAlreadyHeardTrackAcrossBothModes() {
        queue.mark("a")
        assertEquals("b", queue.next(lists, "a", MusicQueue.Mode.SMALL, history, true))
        assertNull(queue.next(lists, "b", MusicQueue.Mode.SMALL, history, true))
        val first = queue.next(lists, "b", MusicQueue.Mode.LARGE, history, true)
        val second = queue.next(lists, first!!, MusicQueue.Mode.LARGE, history, true)
        assertEquals(setOf("c", "d"), setOf(first, second))
        assertNull(queue.next(lists, second!!, MusicQueue.Mode.LARGE, history, true))
    }

    @Test fun probingTheNextActionDoesNotConsumeADraw() {
        repeat(4) { assertEquals("b", queue.next(lists, "a", MusicQueue.Mode.SMALL, history, false)) }
        assertEquals("b", queue.next(lists, "a", MusicQueue.Mode.SMALL, history, true))
    }
}
