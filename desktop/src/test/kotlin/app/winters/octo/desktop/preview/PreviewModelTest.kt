package app.winters.octo.desktop.preview

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.songJson
import app.winters.octo.subsonic.LibraryActionResult
import app.winters.octo.subsonic.LibraryActionState
import app.winters.octo.subsonic.PreviewChoice
import app.winters.octo.subsonic.PreviewSource
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

// "Choose version" against a pretend Octo server: the list, a pick, and
// what the dialog says when there is no list.
class PreviewModelTest {
    private val server = FakeServer()
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val song = Song("ext1", "Lift", artist = "Radiohead", isExternal = true)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private suspend fun until(what: String, test: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!test()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    private fun choices() = server.answer(
        "getPreviewChoices",
        """"previewChoices":{"id":"ext1","choice":[
            {"videoId":"a1","title":"Lift","channel":"Radiohead","lengthSeconds":227,"source":"YouTube Music","current":true},
            {"videoId":"b2","title":"Lift (Live at Glastonbury)","channel":"Someone","lengthSeconds":301,"source":"YouTube"}
        ]}""",
        type = "octo",
    )

    @Test
    fun theServersChoicesAreListedInItsOrder() = runBlocking {
        choices()
        val model = PreviewModel(server.client(), scope)
        model.load(song)
        until("the list") { model.choices.value != null }
        val listed = model.choices.value!!
        assertEquals("ext1", listed.id)
        assertEquals(listOf("a1", "b2"), listed.choice.map { it.videoId })
        assertEquals(listOf(true, false), listed.choice.map { it.current })
        assertNull(model.problem.value)
        assertEquals("id=ext1", server.calls.single().url.query?.split("&")?.first { it.startsWith("id=") })
        model.close()
    }

    @Test
    fun aPickGoesToTheServerWithTheVideoAndItsLength_AndTicksTheRow() = runBlocking {
        choices()
        server.answer("setPreview", """"song":${songJson("ext1", "Lift", artist = "Radiohead", duration = 301)}""", type = "octo")
        var heard: Triple<Song, PreviewChoice, Song>? = null
        val model = PreviewModel(server.client(), scope, chosen = { s, c, a -> heard = Triple(s, c, a) })
        model.load(song)
        until("the list") { model.choices.value != null }
        val live = model.choices.value!!.choice[1]
        model.choose(song, live)
        until("the pick") { heard != null }
        assertEquals(301, heard!!.third.duration)
        assertEquals("b2", heard!!.second.videoId)
        val sent = server.calls.last().url
        assertEquals("setPreview", sent.pathSegments.last())
        assertEquals("ext1", sent.queryParameter("id"))
        assertEquals("b2", sent.queryParameter("videoId"))
        assertEquals("301", sent.queryParameter("length"))
        until("the tick") { model.choices.value?.choice?.map { it.current } == listOf(false, true) }
        assertEquals(false, model.busy.value)
        model.close()
    }

    @Test
    fun aRefusalIsSaidInPlainWords() = runBlocking {
        server.fail("getPreviewChoices", 70, "not a song found online")
        server.fail("setPreview", 0, "the shim is down")
        val said = mutableListOf<String>()
        val model = PreviewModel(server.client(), scope, notify = { said += it })
        model.load(song)
        until("the problem") { model.problem.value != null }
        assertEquals("That isn't on the server any more.", model.problem.value)
        assertNull(model.choices.value)
        model.choose(song, PreviewChoice("b2", "Lift"))
        until("the refusal") { said.isNotEmpty() }
        assertEquals("Couldn't choose that version: The server said: the shim is down", said.single())
        // Forgotten once the dialog shuts.
        model.forget()
        assertNull(model.problem.value)
        model.close()
    }

    // A library song, on a server from OCTO_PREVIEW_LIBRARY_VERSION: the
    // list says so, and a pick asks for the file to be replaced.
    @Test
    fun aLibrarySongsListSaysSo_AndAPickReplacesItsFile() = runBlocking {
        server.answer(
            "getPreviewChoices",
            """"previewChoices":{"id":"lib1","library":true,"choice":[
                {"videoId":"a1","title":"Lift","channel":"Radiohead","lengthSeconds":227,"source":"YouTube Music","current":true},
                {"videoId":"b2","title":"Lift (Live at Glastonbury)","channel":"Someone","lengthSeconds":301,"source":"YouTube"}
            ]}""",
            type = "octo",
        )
        server.answer("libraryAction", """"libraryAction":{"id":"lib1","action":"replace","state":"queued","detail":"Queued."}""", type = "octo")
        val inLibrary = Song("lib1", "Lift", artist = "Radiohead")
        var heard: Triple<Song, PreviewChoice, LibraryActionResult>? = null
        val model = PreviewModel(server.client(), scope, replaced = { s, c, r -> heard = Triple(s, c, r) })
        model.load(inLibrary)
        until("the list") { model.choices.value != null }
        assertTrue(model.choices.value!!.library)
        // A song found online is never marked as the library's.
        choices()
        model.load(song)
        until("the other list") { model.choices.value?.id == "ext1" }
        assertFalse(model.choices.value!!.library)

        model.replace(inLibrary, PreviewChoice("b2", "Lift (Live at Glastonbury)", lengthSeconds = 301))
        until("the answer") { heard != null }
        assertEquals(LibraryActionState.Queued, heard!!.third.outcome)
        assertEquals("b2", heard!!.second.videoId)
        val sent = server.calls.last().url
        assertEquals("libraryAction", sent.pathSegments.last())
        assertEquals("lib1", sent.queryParameter("id"))
        assertEquals("replace", sent.queryParameter("action"))
        assertEquals("b2", sent.queryParameter("videoId"))
        assertEquals("301", sent.queryParameter("length"))
        until("not busy") { !model.busy.value }
        model.close()
    }

    @Test
    fun aReplacementTheServerRefusesIsSaidInPlainWords() = runBlocking {
        server.fail("libraryAction", 0, "not allowed")
        val said = mutableListOf<String>()
        val model = PreviewModel(server.client(), scope, notify = { said += it })
        model.replace(Song("lib1", "Lift"), PreviewChoice("b2", "Lift"))
        until("the refusal") { said.isNotEmpty() }
        assertEquals("Couldn't replace the file: The server said: not allowed", said.single())
        model.close()
    }

    @Test
    fun theQuestionAndNoticesAroundAReplacement() {
        val lift = Song("lib1", "Lift")
        val live = PreviewChoice("b2", "Lift (Live)")
        assertEquals("Lift is replaced by Lift (Live). The file you have now goes to the trash for 30 days.", replaceBody(lift, live, 30))
        assertEquals("Lift is replaced by Lift (Live). The file you have now goes to the trash for 1 day.", replaceBody(lift, live, 1))
        assertEquals("Lift is replaced by the chosen video. The file you have now goes to the trash until someone clears it.", replaceBody(lift, PreviewChoice("b2"), 0))
        assertEquals("Fetching Lift (Live) for Lift", fetchingNotice(lift, live))
        assertEquals("Queue is full.", replaceRefusedLine(LibraryActionResult(state = "skipped", detail = "Queue is full.")))
        assertEquals("Couldn't replace the file (failed).", replaceRefusedLine(LibraryActionResult(state = "failed")))
    }

    @Test
    fun theRowPickedIsTheOnlyCurrentOne() {
        val listed = listOf(PreviewChoice("a1", current = true), PreviewChoice("b2"), PreviewChoice("c3"))
        assertEquals(listOf(false, true, false), withCurrent(listed, "b2").map { it.current })
        // An id not in the list leaves none ticked.
        assertEquals(listOf(false, false, false), withCurrent(listed, "zz").map { it.current })
    }

    @Test
    fun aChoiceIsDescribedByItsChannelAndLength() {
        assertEquals("Radiohead · 3:47", choiceLine(PreviewChoice("a1", "Lift", channel = "Radiohead", lengthSeconds = 227)))
        assertEquals("Radiohead", choiceLine(PreviewChoice("a1", "Lift", channel = "Radiohead")))
        assertEquals("1:02:03", choiceLine(PreviewChoice("a1", "Lift", channel = " ", lengthSeconds = 3723)))
        assertEquals("", choiceLine(PreviewChoice("a1", "Lift", lengthSeconds = 0)))
        assertEquals("Plays from Lift now", playsFromNotice(PreviewChoice("a1", "Lift", source = PreviewSource.YOUTUBE_MUSIC)))
        assertEquals("Plays from the chosen video now", playsFromNotice(PreviewChoice("a1")))
    }
}
