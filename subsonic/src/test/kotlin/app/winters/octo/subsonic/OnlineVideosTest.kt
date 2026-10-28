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

// Octo's search also finds YouTube videos: a search says what it looks for,
// and a video's row says it is one.
class OnlineVideosTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("winters", "secret"), OkHttpClient())

    private val answer = """{"subsonic-response":{"status":"ok","searchResult3":{"song":[
        {"id":"v1","title":"Ba Sing Se | Bustling City Ambience","artist":"Ambience Academy","duration":4512,"octoVideo":true},
        {"id":"s1","title":"Another Love","artist":"Tom Odell"}]}}}"""

    @Test
    fun aSearchSaysWhatItLooksForAndAVideoIsMarked() = runTest {
        server.enqueue(MockResponse.Builder().body(answer).build())
        val songs = client().search("ba sing se", find = OnlineFind.Videos).song
        assertEquals("videos", server.takeRequest().url.queryParameter("octoFind"))
        assertTrue(songs[0].octoVideo)
        assertEquals(4512, songs[0].duration)
        assertFalse(songs[1].octoVideo)
    }

    @Test
    fun leftToTheServerASearchSaysNothing() = runTest {
        server.enqueue(MockResponse.Builder().body(answer).build())
        client().search("ba sing se")
        assertNull(server.takeRequest().url.queryParameter("octoFind"))
    }
}
