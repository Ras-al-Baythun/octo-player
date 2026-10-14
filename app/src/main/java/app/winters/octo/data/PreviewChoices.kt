package app.winters.octo.data

import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.findId
import app.winters.octo.catalog.isFind
import app.winters.octo.discovery.FindLengths
import app.winters.octo.discovery.knownLengthMs
import app.winters.octo.playback.PlaybackConnection
import app.winters.octo.playback.Streams
import app.winters.octo.subsonic.LibraryActionState
import app.winters.octo.subsonic.PreviewChoice
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.preview.DRY_RUN_NOTICE
import app.winters.octo.ui.preview.fetchingVersionLine
import app.winters.octo.ui.preview.replaceRefusedLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// What the Choose version sheet is open for: a song by its id in the app
// and on the server. A library song (`library`) is not played from the
// video picked: its file is replaced by a download of it.
data class VersionSheet(val trackId: String, val serverId: String, val library: Boolean = false)

// The videos a song found online could play from, on the signed-in Octo
// server, and the pick of one. Only on a server that lists octoPreview.
// The list is asked for when the sheet opens; a pick makes the song play
// from that video from then on, gives the find the video's length, and
// starts the song over if it is the one on now. From the extension's
// second version a library song has the list too, and a pick, once the
// listener agrees, has the server swap the file for the video's download.
@Singleton
class PreviewChoices @Inject constructor(
    private val sessions: SessionRepository,
    private val online: OnlineDao,
    private val lengths: FindLengths,
    private val streams: Streams,
    private val playback: PlaybackConnection,
    private val feedback: Feedback,
    private val files: LibraryFiles,
    private val serverDownloads: ServerDownloads,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Whether the signed-in server lets the listener pick the video.
    val supported: Flow<Boolean> = sessions.state.map { (it as? SessionState.SignedIn)?.session?.choosesVersions == true }

    // Whether a library song's file can be swapped for a video's download:
    // the server lists songs' choices for the library and replaces files.
    val replaces: Flow<Boolean> = combine(sessions.state, files.actions) { state, actions ->
        (state as? SessionState.SignedIn)?.session?.replacesVersions == true && actions?.canReplace == true
    }

    // How many days the server keeps a replaced file in its trash.
    val keepDays: Flow<Int> = files.actions.map { it?.keepDays ?: 0 }

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

    private val _asking = MutableStateFlow<PreviewChoice?>(null)

    // The video a library song's file would be replaced with, while the
    // listener is asked.
    val asking: StateFlow<PreviewChoice?> = _asking

    private var loadJob: Job? = null

    private fun client() = (sessions.state.value as? SessionState.SignedIn)?.session?.client

    // Opens the sheet for a song, by its id in the app: a song found online
    // at once, a library song once its copy on the server is known.
    fun open(trackId: String) {
        if (isFind(trackId)) {
            val serverId = trackId.removePrefix(FIND_PREFIX)
            _view.value = VersionSheet(trackId, serverId)
            load(serverId)
            return
        }
        scope.launch {
            val serverId = files.serverIds(listOf(trackId))[trackId]
            if (serverId == null) {
                feedback.show("This server has no copy of the song")
                return@launch
            }
            _view.value = VersionSheet(trackId, serverId, library = true)
            load(serverId)
        }
    }

    fun close() {
        loadJob?.cancel()
        _asking.value = null
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
                val found = client()?.previewChoices(serverId)
                if (_view.value?.serverId == serverId) {
                    _choices.value = found?.choice ?: emptyList()
                    // The server knows the song as the library's: a pick replaces its file.
                    if (found?.library == true) _view.update { v -> if (v?.serverId == serverId) v.copy(library = true) else v }
                }
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

    // A library song's video was tapped: the listener is asked first.
    fun ask(choice: PreviewChoice) {
        if (_choosing.value != null) return
        _asking.value = choice
    }

    fun cancelAsk() {
        _asking.value = null
    }

    // The listener agreed: the server is asked to swap the file, named by
    // the song's `title` in the line said after. The server queues the
    // download and answers at once; the sheet closes on any answer, and
    // the line says how it went, with the server downloads sheet a tap
    // away while the download runs.
    fun confirmReplace(title: String) {
        val view = _view.value ?: return
        val choice = _asking.value ?: return
        val client = client() ?: return
        if (_choosing.value != null) return
        _asking.value = null
        _choosing.value = choice.videoId
        _problem.value = null
        scope.launch {
            try {
                val result = client.replaceVersion(view.serverId, choice.videoId, choice.lengthSeconds)
                close()
                when (result.outcome) {
                    LibraryActionState.Queued -> {
                        val line = fetchingVersionLine(title, choice.title)
                        if (serverDownloads.supported.value) feedback.show(line, "Show", onAction = { serverDownloads.open() }) else feedback.show(line)
                    }
                    LibraryActionState.Rehearsed -> feedback.show(DRY_RUN_NOTICE)
                    else -> feedback.show(replaceRefusedLine(result.state, result.detail))
                }
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
