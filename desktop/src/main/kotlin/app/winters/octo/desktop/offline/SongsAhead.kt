package app.winters.octo.desktop.offline

import app.winters.octo.desktop.audio.LOCAL_PREFIX
import app.winters.octo.desktop.audio.SongAddress
import app.winters.octo.desktop.library.entriesAhead
import app.winters.octo.desktop.player.DesktopPlayer
import app.winters.octo.desktop.system.isOpenedFile
import app.winters.octo.offline.fileSafe
import app.winters.octo.subsonic.Song
import app.winters.octo.playback.savedAhead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

// The songs to save ahead: the next `count` in play order that come from
// the server, with a length (a stream never ends) no longer than
// `maxMinutes` (0 is no limit) and no copy kept here.
fun songsToSave(next: List<Song>, maxMinutes: Int = 0, kept: (String) -> Boolean): List<Song> =
    next.filter {
        !it.id.startsWith(LOCAL_PREFIX) && !isOpenedFile(it.id) && it.duration > 0 &&
            savedAhead(it.duration.toLong(), maxMinutes) && !kept(it.id)
    }.distinctBy { it.id }

// Saves the next songs of the queue on this computer while music plays, as
// the phone does, so they start at once and still play when the connection
// drops (a train, a tunnel). They go in their own folder, one file a song,
// fetched from the same address playing uses; whatever is no longer the
// song on or one of the next is deleted, so the folder never grows, and it
// starts empty, what an earlier run left being stale by then. Each
// song saved is handed to the engine at once (`saved`), so it plays from
// the file. A failed fetch (no connection) stops the round; the next change
// to the queue tries again.
class SongsAhead(
    private val folder: File,
    private val address: (Song) -> SongAddress?,
    // The file's ending: what the stream sends, for the engine to know it by.
    private val suffix: (Song) -> String,
    private val http: () -> OkHttpClient,
) {
    // The songs saved, by id. Asked for every song each time the queue goes
    // to the engine, so it is never the folder that is asked.
    private val files = ConcurrentHashMap<String, File>()

    init {
        folder.listFiles()?.forEach { it.delete() }
    }

    // The saved copy of a song, if it is all here.
    fun fileFor(id: String): File? = files[id]

    // Follows the player until cancelled, keeping `count` songs saved ahead.
    suspend fun follow(
        player: DesktopPlayer,
        count: () -> Int,
        kept: (String) -> Boolean,
        maxMinutes: () -> Int = { 0 },
        saved: () -> Unit,
    ) {
        player.state
            .map { state -> state.current?.song?.id to songsToSave(entriesAhead(state, count()).map { it.song }, maxMinutes(), kept) }
            .distinctUntilChanged()
            .collectLatest { (current, next) ->
                withContext(Dispatchers.IO) {
                    tidy(setOfNotNull(current) + next.map { it.id })
                    for (song in next) {
                        coroutineContext.ensureActive()
                        if (fileFor(song.id) != null) continue
                        if (!save(song)) break
                        saved()
                    }
                }
            }
    }

    // Deletes the files of every song but those named.
    private fun tidy(keep: Set<String>) {
        files.keys.filter { it !in keep }.forEach { id -> files.remove(id)?.delete() }
        folder.listFiles { f -> f.name.endsWith(PART) }?.forEach { it.delete() }
    }

    // Fetches one song whole; false when fetching should stop.
    private suspend fun save(song: Song): Boolean {
        val where = address(song) ?: return false
        folder.mkdirs()
        val target = File(folder, fileSafe(song.id) + "." + suffix(song))
        val part = File(folder, target.name + PART)
        return try {
            val request = Request.Builder().url(where.source).apply { where.headers.forEach { (k, v) -> header(k, v) } }.build()
            http().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                part.outputStream().use { out ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            part.renameTo(target).also { if (it) files[song.id] = target }
        } catch (e: IOException) {
            false
        } finally {
            part.delete()
        }
    }

    private companion object {
        const val PART = ".part"
    }
}
