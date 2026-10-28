package app.winters.octo.playback

// The queue positions that play after `current`, at most `count` of them,
// following the player's own steps: `next` gives the position after one
// in play order, shuffled or not, wrapping round to the start with repeat
// all, or null at the end. The song on is never among them, nor any
// position twice.
fun nextInPlayOrder(current: Int, count: Int, next: (Int) -> Int?): List<Int> {
    val found = ArrayList<Int>()
    var position = current
    while (found.size < count) {
        position = next(position) ?: break
        if (position == current || position in found) break
        found += position
    }
    return found
}

// The songs that play after the one on, at most `count` of them. `order` is
// the queue in the order it plays (shuffled or not) and `at` where in it
// the song on is, or -1 when none is and it plays from the start. With
// `repeatAll` the end wraps round to the start. Repeat one moves on as no
// repeat does, since a skip leaves the song.
fun <T> nextInPlayOrder(order: List<T>, at: Int, repeatAll: Boolean, count: Int): List<T> {
    if (count <= 0 || order.isEmpty()) return emptyList()
    if (at !in order.indices) return order.take(count)
    val ranks = nextInPlayOrder(at, count) { rank ->
        when {
            rank + 1 < order.size -> rank + 1
            repeatAll -> 0
            else -> null
        }
    }
    return ranks.map(order::get)
}

// The longest a song may run, in minutes, to be saved ahead of playing;
// 0 is no limit. Longer songs and videos still go in the queue and stream
// when they play: this only keeps an hour of ambience off mobile data.
val AHEAD_MAX_MINUTES = listOf(0, 4, 6, 8, 10, 15)
const val AHEAD_MAX_DEFAULT = 8

// Whether a song of `lengthSeconds` (null or 0 when not known) is saved
// ahead under a limit of `maxMinutes`. A length not known is taken as a song's.
fun savedAhead(lengthSeconds: Long?, maxMinutes: Int): Boolean =
    maxMinutes <= 0 || lengthSeconds == null || lengthSeconds <= 0 || lengthSeconds <= maxMinutes * 60L

// How a limit reads in a list of them.
fun aheadMaxLabel(maxMinutes: Int): String = if (maxMinutes <= 0) "No limit" else "$maxMinutes min"
