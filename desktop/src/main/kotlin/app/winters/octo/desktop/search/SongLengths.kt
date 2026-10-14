package app.winters.octo.desktop.search

import app.winters.octo.discovery.knownLengthMs
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Songs found online often list without a length (or with Octo's guess),
// and the server looks one up only when asked about the song on its own.
// A page of them asks for a few, so their rows fill in soon after.

// How many of a list's songs are asked about, and how many at once.
const val LENGTHS_PER_LIST = 10
private const val LENGTHS_AT_ONCE = 3

// The songs in a list to ask about: those found online (`outside`) listed
// without a length, whose length is not known yet (`known`), the first
// `limit` of them, each once.
fun songsWithoutLength(
    songs: List<Song>,
    outside: (Song) -> Boolean,
    known: (String) -> Boolean = { false },
    limit: Int = LENGTHS_PER_LIST,
): List<Song> =
    songs.filter { knownLengthMs(it) == 0L && !known(it.id) && outside(it) }.distinctBy { it.id }.take(limit)

// Asks the server about each song and hands on each length it answers, in
// milliseconds, a few songs at a time. A song it cannot say about is left
// as it was. Runs until every answer is in, so cancelling the caller
// cancels the asking.
suspend fun refreshLengths(songs: List<Song>, client: SubsonicClient, onLength: (String, Long) -> Unit) {
    if (songs.isEmpty()) return
    val gate = Semaphore(LENGTHS_AT_ONCE)
    coroutineScope {
        songs.forEach { song ->
            launch {
                gate.withPermit {
                    val ms = try {
                        knownLengthMs(client.song(song.id))
                    } catch (e: SubsonicException) {
                        return@withPermit
                    }
                    if (ms > 0) onLength(song.id, ms)
                }
            }
        }
    }
}
