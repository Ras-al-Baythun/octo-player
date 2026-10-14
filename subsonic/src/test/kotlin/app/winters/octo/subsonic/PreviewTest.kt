package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The octoPreview extension: the videos a song found online could play
// from, and the pick of one.
class PreviewTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    @Test
    fun theChoicesParseInTheServersOrder() = runTest {
        answer(
            """{"subsonic-response":{"status":"ok","version":"1.16.1","previewChoices":{"id":"yt:abc","choice":[
            {"videoId":"v1","title":"Song (Official Audio)","channel":"Artist - Topic","lengthSeconds":215,"source":"YouTube Music","current":true},
            {"videoId":"v2","title":"Song (Live)","channel":"Artist","lengthSeconds":301,"source":"YouTube"},
            {"videoId":"v3","title":"Song"}
            ]}}}""",
        )
        val choices = client().previewChoices("yt:abc")
        val url = server.takeRequest().url
        assertEquals("/rest/getPreviewChoices", url.encodedPath)
        assertEquals("yt:abc", url.queryParameter("id"))

        assertEquals("yt:abc", choices.id)
        assertEquals(listOf("v1", "v2", "v3"), choices.choice.map { it.videoId })
        val first = choices.choice[0]
        assertEquals("Song (Official Audio)", first.title)
        assertEquals("Artist - Topic", first.channel)
        assertEquals(215, first.lengthSeconds)
        assertEquals(PreviewSource.YOUTUBE_MUSIC, first.source)
        assertTrue(first.current)
        assertEquals(listOf(false, false), choices.choice.drop(1).map { it.current })
        // A bare choice: a plain video with nothing else known about it.
        val bare = choices.choice[2]
        assertNull(bare.channel)
        assertNull(bare.lengthSeconds)
        assertEquals(PreviewSource.YOUTUBE, bare.source)
        // A version 1 server never says "library": it only lists songs found online.
        assertFalse(choices.library)
    }

    // From version 2 a library song has choices too, with the video its
    // file was downloaded from marked when known.
    @Test
    fun aLibrarySongsChoicesSaySo() = runTest {
        answer(
            """{"subsonic-response":{"status":"ok","previewChoices":{"id":"lib-1","library":true,"choice":[
            {"videoId":"v1","title":"Song (Official Audio)","channel":"Artist - Topic","lengthSeconds":215,"source":"YouTube Music","current":true},
            {"videoId":"v2","title":"Song (Live)","lengthSeconds":301}
            ]}}}""",
        )
        val choices = client().previewChoices("lib-1")
        assertTrue(choices.library)
        assertEquals("lib-1", choices.id)
        assertEquals(listOf(true, false), choices.choice.map { it.current })
    }

    // The shim is down: the list is empty, not an error.
    @Test
    fun noChoicesIsAnEmptyList() = runTest {
        answer("""{"subsonic-response":{"status":"ok","previewChoices":{"id":"yt:abc"}}}""")
        assertTrue(client().previewChoices("yt:abc").choice.isEmpty())
    }

    @Test
    fun aLibrarySongHasNoChoices() = runTest {
        answer("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Not a song found online"}}}""")
        try {
            client().previewChoices("lib-1")
            error("Expected an error")
        } catch (e: SubsonicException.NotFound) {
            assertEquals("Not a song found online", e.message)
        }
    }

    @Test
    fun pickingAVideoSendsItWithItsLengthAndAnswersTheSong() = runTest {
        answer(
            """{"subsonic-response":{"status":"ok","song":{"id":"yt:abc","title":"Song","artist":"Artist","duration":301,"isExternal":true,"suffix":"m4a","bitRate":128}}}""",
        )
        val song = client().setPreview("yt:abc", "v2", 301)
        val url = server.takeRequest().url
        assertEquals("/rest/setPreview", url.encodedPath)
        assertEquals("yt:abc", url.queryParameter("id"))
        assertEquals("v2", url.queryParameter("videoId"))
        assertEquals("301", url.queryParameter("length"))

        assertEquals("yt:abc", song.id)
        assertEquals("Song", song.title)
        assertEquals(301, song.duration)
        assertTrue(song.isExternal)
    }

    // A choice listed without a length leaves the server to look it up.
    @Test
    fun pickingWithoutALengthLeavesItOut() = runTest {
        answer("""{"subsonic-response":{"status":"ok","song":{"id":"yt:abc","title":"Song","duration":215}}}""")
        assertEquals(215, client().setPreview("yt:abc", "v3").duration)
        assertNull(server.takeRequest().url.queryParameter("length"))
    }
}
