package app.winters.octo.desktop.preview

import androidx.compose.runtime.Stable
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.server.userMessage
import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.subsonic.LibraryActionState
import app.winters.octo.subsonic.PreviewChoice
import app.winters.octo.subsonic.PreviewChoices
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.ui.preview.fetchingVersionLine
import app.winters.octo.ui.preview.replaceVersionBody
import app.winters.octo.ui.preview.replaceRefusedLine as refusedLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The menu row that opens the choice.
const val CHOOSE_VERSION = "Choose version…"

// The dialog's heading.
const val CHOOSE_VERSION_TITLE = "Choose version"

// Shown while the server looks, and when it found nothing.
const val LOOKING_FOR_VERSIONS = "Looking for versions…"
const val NO_VERSIONS = "Nothing found for this song"

// The choices with `videoId` marked as the one the song plays from now,
// and no other.
fun withCurrent(choices: List<PreviewChoice>, videoId: String): List<PreviewChoice> =
    choices.map { it.copy(current = it.videoId == videoId) }

// A choice's second line: who put it up and how long it runs.
fun choiceLine(choice: PreviewChoice): String =
    listOfNotNull(choice.channel?.takeIf { it.isNotBlank() }, choice.lengthSeconds?.let(::lengthText)?.takeIf { it.isNotEmpty() })
        .joinToString(" · ")

// The notice once a choice took.
fun playsFromNotice(choice: PreviewChoice): String = "Plays from ${choice.title.ifBlank { "the chosen video" }} now"

// The words around replacing a library song's file are the phone's too
// (app.winters.octo.ui.preview); here they take the song and the choice.
fun replaceBody(song: Song, choice: PreviewChoice, keepDays: Int): String = replaceVersionBody(song.title, choice.title, keepDays)

// The notice once the server took a replacement on.
fun fetchingNotice(song: Song, choice: PreviewChoice): String = fetchingVersionLine(song.title, choice.title)

// The line for a replacement the server did not take on.
fun replaceRefusedLine(result: LibraryActionResult): String = refusedLine(result.state, result.detail)

// "Choose version…" for one song found online, on an Octo server that
// lists octoPreview: the videos the song could play from, and the pick.
// One song at a time; asking about another forgets the last. Made with
// each connection and closed with it. `chosen` hears each pick the server
// took, with the song as the server lists it now (its length that of the
// video); a refusal goes to `notify` in plain words. For a library song,
// from OCTO_PREVIEW_LIBRARY_VERSION, a pick instead asks the server to
// swap the file for the video's download, and `replaced` hears how the
// server answered (queued, rehearsed or refused).
@Stable
class PreviewModel(
    private val client: SubsonicClient,
    parent: CoroutineScope,
    private val chosen: (Song, PreviewChoice, Song) -> Unit = { _, _, _ -> },
    private val notify: (String) -> Unit = {},
    private val replaced: (Song, PreviewChoice, LibraryActionResult) -> Unit = { _, _, _ -> },
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    private val _choices = MutableStateFlow<PreviewChoices?>(null)

    // The choices for the song asked about last, once the server answered;
    // null while it looks, and after forget().
    val choices: StateFlow<PreviewChoices?> = _choices

    private val _problem = MutableStateFlow<String?>(null)

    // Why there is no list, in plain words.
    val problem: StateFlow<String?> = _problem

    private val _busy = MutableStateFlow(false)

    // Whether a pick is on its way to the server.
    val busy: StateFlow<Boolean> = _busy

    private var loading: Job? = null

    // Asks the server which videos the song could play from.
    fun load(song: Song) {
        loading?.cancel()
        _choices.value = null
        _problem.value = null
        loading = scope.launch {
            try {
                _choices.value = client.previewChoices(song.id)
            } catch (e: SubsonicException) {
                _problem.value = e.userMessage()
            }
        }
    }

    // Makes the song play from this video from now on. The list, when it
    // is still this song's, ticks the pick once the server took it.
    fun choose(song: Song, choice: PreviewChoice) {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                val answered = client.setPreview(song.id, choice.videoId, choice.lengthSeconds)
                _choices.update { listed -> if (listed?.id == song.id) listed.copy(choice = withCurrent(listed.choice, choice.videoId)) else listed }
                chosen(song, choice, answered)
            } catch (e: SubsonicException) {
                notify("Couldn't choose that version: ${e.userMessage()}")
            } finally {
                _busy.value = false
            }
        }
    }

    // Asks the server to swap the library song's file for this video's
    // download. The server queues it and answers at once; the outcome
    // goes to `replaced`, a refusal to `notify`.
    fun replace(song: Song, choice: PreviewChoice) {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                replaced(song, choice, client.replaceVersion(song.id, choice.videoId, choice.lengthSeconds))
            } catch (e: SubsonicException) {
                notify("Couldn't replace the file: ${e.userMessage()}")
            } finally {
                _busy.value = false
            }
        }
    }

    // The dialog closed: nothing more is asked for it.
    fun forget() {
        loading?.cancel()
        loading = null
        _choices.value = null
        _problem.value = null
    }

    // The server is left: nothing more is asked of it.
    fun close() {
        scope.cancel()
    }
}
