package local.bookdoctorc

import kotlin.math.sqrt
import kotlin.random.Random

internal class MusicQueue(private val random: Random = Random.Default) {
    enum class Mode(val label: String) {
        SEQUENCE("顺序播放"), LOOP("列表循环"), SMALL("小随机"), LARGE("大随机")
    }
    private val heard = HashSet<String>()

    fun mark(id: String) { heard.add(id) }

    fun next(lists: List<List<String>>, current: String, mode: Mode, plays: (String) -> Int, commit: Boolean): String? {
        val listIndex = lists.indexOfFirst { current in it }
        val list = lists.getOrNull(listIndex).orEmpty()
        val position = list.indexOf(current)
        if (mode == Mode.SEQUENCE) return list.getOrNull(position + 1)
            ?: lists.drop(listIndex + 1).firstOrNull { it.isNotEmpty() }?.first()
        if (mode == Mode.LOOP) return list.getOrNull(position + 1) ?: list.firstOrNull()
        val candidates = (if (mode == Mode.SMALL) list else lists.flatten())
            .distinct().filter { it != current && it !in heard }
        if (candidates.isEmpty()) return null
        if (!commit) return candidates.first()
        val weights = candidates.map { 1.0 + sqrt(plays(it).coerceAtLeast(0).toDouble()).coerceAtMost(7.0) }
        var point = random.nextDouble(weights.sum())
        val index = weights.indexOfFirst { weight -> point -= weight; point < 0 }
        return candidates[if (index >= 0) index else candidates.lastIndex].also(::mark)
    }
}
