package app.winters.octo.data

import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.FindLengths
import app.winters.octo.discovery.knownLengthMs
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.Streams
import app.winters.octo.subsonic.PreviewChoice
import app.winters.octo.ui.common.Feedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// What the Choose version sheet is open for: a song found online, by its
// id in the app and on the server.
data class VersionSheet(val trackId: String, val serverId: String)

// The videos a song found online could play from, on the signed-in Octo
// server, and the pick of one. Only on a server that lists octoPreview.
// The list is asked for when the sheet opens; a pick makes the song play
// from that video from then on, gives the find the video's length, and
// starts the song over if it is the one on now.
@Singleton
class PreviewChoices @Inject constructor(
    private val sessions: SessionRepository,
    private val online: OnlineDao,
    private val lengths: FindLengths,
    private val streams: Streams,
    private val playback: PlaybackConnection,
    private val feedback: Feedback,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Whether the signed-in server lets the listener pick the video.
    val supported: Flow<Boolean> = sessions.state.map { (it as? SessionState.SignedIn)?.session?.choosesVersions == true }

    private val _view = MutableStateFlow<VersionSheet?>(null)

    // What the sheet is open for, or null while it is closed.
    val view: StateFlow<VersionSheet?> = _view

    private val _choices = MutableStateFlow<List<PreviewChoice>>(emptyList())

    // The videos the song could play from, in the server's order.
    val choices: StateFlow<List<PreviewChoice>> = _choices

    private val _loading = MutableStateFlow(false)

    // Whether the list is still being asked for.
    val loading: StateFlow<Boolean> = _loading

    private val _problem = MutableStateFlow<String?>(null)

    // Why the list could not be asked for, or the pick not made.
    val problem: StateFlow<String?> = _problem

    private val _choosing = MutableStateFlow<String?>(null)

    // The video being picked, while the server is told.
    val choosing: StateFlow<String?> = _choosing

    private var loadJob: Job? = null

    private fun client() = (sessions.state.value as? SessionState.SignedIn)?.session?.client

    // Opens the sheet for a song found online, by its id in the app.
    fun open(trackId: String) {
        if (!isFind(trackId)) return
        val serverId = trackId.removePrefix(FIND_PREFIX)
        _view.value = VersionSheet(trackId, serverId)
        load(serverId)
    }

    fun close() {
        loadJob?.cancel()
        _view.value = null
    }

    // Asks the server for the videos, in place of whatever was listed.
    fun load(serverId: String) {
        loadJob?.cancel()
        _choices.value = emptyList()
        _problem.value = null
        _loading.value = true
        loadJob = scope.launch {
            try {
                val found = client()?.previewChoices(serverId)?.choice ?: emptyList()
                if (_view.value?.serverId == serverId) _choices.value = found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_view.value?.serverId == serverId) _problem.value = e.userMessage()
            } finally {
                _loading.value = false
            }
        }
    }

    // Makes the song play from this video from now on. The find takes the
    // video's length, so its rows show it; the song on now, if it is this
    // one, starts over from the new video; and a line says so.
    fun choose(serverId: String, choice: PreviewChoice) {
        val client = client() ?: return
        if (_choosing.value != null) return
        _choosing.value = choice.videoId
        _problem.value = null
        scope.launch {
            try {
                val song = client.setPreview(serverId, choice.videoId, choice.lengthSeconds)
                val id = findId(serverId)
                val ms = knownLengthMs(song)
                if (ms > 0) {
                    try {
                        lengths.take(id, ms)
                    } catch (e: Exception) {
                        // Storage said no; the row still shows it.
                    }
                }
                _choices.update { list -> list.map { it.copy(current = it.videoId == choice.videoId) } }
                // The old video's saved copies would play the old sound.
                online.song(id)?.let { find -> streams.forgetSaved(find.sourceId, find.nativeId, find.mimeType, find.bitrate?.times(1000)) }
                if (playback.now.value.trackId == id) playback.reopen(id, choice.videoId)
                feedback.show("Plays from ${choice.title.ifBlank { "the chosen video" }} now")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _problem.value = e.userMessage()
            } finally {
                _choosing.value = null
            }
        }
    }
}
