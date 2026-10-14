package app.winters.octo.desktop.preview

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.songJson
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
