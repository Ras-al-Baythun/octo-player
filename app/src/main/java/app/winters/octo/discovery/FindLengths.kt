package app.winters.octo.discovery

import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.playback.RealLengths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

// A song's length as a row shows it: the real one once playing it found
// its listing wrong, else the listed one. Zero when neither is known.
fun shownLengthMs(listedMs: Long, learnedMs: Long?): Long = learnedMs ?: listedMs.coerceAtLeast(0)

// How many finds without a length one list asks the server about, and how
// many at a time: the server looks each one up, which takes it a moment.
private const val REFRESH_AT_MOST = 10
private const val REFRESH_AT_ONCE = 3

// Lengths learned by playing songs. Half the finds come with no length,
// and a find's listed length can be another copy's; the phone's player
// knows the length of the sound once the song opens. A listing more than
// a second off is corrected in memory for every song, so rows and the
// queue already on screen show it, and stored with the find, so it shows
// next time too. A find listed without a length can also be asked of the
// server (`refresh`), which looks the length up when asked for one song.
@Singleton
class FindLengths internal constructor(
    private val store: suspend (String, Long) -> Int,
    private val real: RealLengths,
    // The server's length of a find, by the find's id there, in
    // milliseconds; null when it does not know.
    private val lookup: suspend (String) -> Long? = { null },
) {
    @Inject constructor(online: OnlineDao, real: RealLengths, sessions: SessionRepository) : this(
        online::setLength,
        real,
        { serverId ->
            val client = (sessions.state.value as? SessionState.SignedIn)?.session?.client
            client?.song(serverId)?.let { knownLengthMs(it).takeIf { ms -> ms > 0 } }
        },
    )

    // The corrected lengths, by song id.
    val learned: StateFlow<Map<String, Long>> = real.corrected

    // Finds already stored with their length, so the same song opening
    // again writes nothing.
    private val stored = HashMap<String, Long>()

    // Takes the player's length of a song listed at `listedMs`.
    suspend fun learn(trackId: String, listedMs: Long, playerMs: Long) {
        val ms = real.learn(trackId, listedMs, playerMs) ?: return
        if (!isFind(trackId)) return
        keep(trackId, ms)
    }

    // Takes a length the server answered for a find, whatever it was
    // listed at: stored, and shown by every row.
    suspend fun take(trackId: String, ms: Long) {
        if (!isFind(trackId)) return
        real.learn(trackId, 0, ms) ?: return
        keep(trackId, ms)
    }

    // Asks the server for the lengths of the finds among `songs` listed
    // without one: the first few, a few at a time, so a long list does not
    // flood it. A find the server cannot answer for is left as it is.
    suspend fun refresh(songs: List<TrackEntity>) {
        val known = learned.value
        val asked = songs.filter { isFind(it.id) && it.durationMs <= 0 && it.id !in known }
            .distinctBy { it.id }
            .take(REFRESH_AT_MOST)
        if (asked.isEmpty()) return
        val gate = Semaphore(REFRESH_AT_ONCE)
        coroutineScope {
            for (song in asked) {
                launch {
                    gate.withPermit {
                        val ms = try {
                            lookup(song.id.removePrefix(FIND_PREFIX))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        } ?: return@withPermit
                        try {
                            take(song.id, ms)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Storage said no; the row still shows it.
                        }
                    }
                }
            }
        }
    }

    private suspend fun keep(trackId: String, ms: Long) {
        synchronized(stored) {
            if (stored[trackId] == ms) return
            stored[trackId] = ms
        }
        try {
            store(trackId, ms)
        } catch (e: Exception) {
            synchronized(stored) { stored.remove(trackId) }
            throw e
        }
    }
}
