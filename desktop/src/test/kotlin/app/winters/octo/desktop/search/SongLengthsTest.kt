package app.winters.octo.desktop.search

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.songJson
import app.winters.octo.playback.RealLengths
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
import org.junit.Test

// Songs found online listed without a length get one looked up, a few of
// them, and every row then shows it through the player's real lengths.
class SongLengthsTest {
    private val server = FakeServer()

    @After fun stop() = server.close()

    @Test
    fun onlySongsFoundOnlineWithoutALengthAreAsked_AFewOfThem() {
        val songs = listOf(
            Song("lib", "Known", duration = 200),
            Song("guess", "Guessed", duration = 180, isExternal = true),
            Song("none", "Unlisted", isExternal = true),
            Song("learned", "Learned", isExternal = true),
            Song("none", "Unlisted again", isExternal = true),
            Song("mine", "Mine, unlisted"),
        ) + (1..20).map { Song("more$it", "More $it", isExternal = true) }
        val asked = songsWithoutLength(songs, { it.isExternal }, { it == "learned" })
        assertEquals(LENGTHS_PER_LIST, asked.size)
        assertEquals(listOf("guess", "none", "more1", "more2", "more3", "more4", "more5", "more6", "more7", "more8"), asked.map { it.id })
    }

    @Test
    fun eachLengthTheServerAnswersIsHandedOn_AndARefusalLeavesTheSongAsItWas() = runBlocking {
        server.answerBy("getSong") { request ->
            when (request.url.queryParameter("id")) {
                "a" -> server.ok(""""song":${songJson("a", "A", duration = 251)}""")
                "b" -> server.ok(""""song":${songJson("b", "B", duration = 0)}""")
                else -> server.failed(70, "gone")
            }
        }
        val heard = mutableMapOf<String, Long>()
        refreshLengths(listOf(Song("a", "A"), Song("b", "B"), Song("c", "C")), server.client()) { id, ms -> heard[id] = ms }
        assertEquals(mapOf("a" to 251_000L), heard)
        assertEquals(3, server.calls.count { it.url.pathSegments.last() == "getSong" })
    }

    @Test
    fun aSearchAsksForTheLengthsOfWhatItFoundOnline() = runBlocking {
        server.answer("search3", """"searchResult3":{"song":[${songJson("lib1", "Karma Police", duration = 264)},${songJson("ext1", "Lift", duration = 0)},${songJson("ext2", "Nude", duration = 180)}]}""")
        server.answerBy("getSong") { request ->
            when (request.url.queryParameter("id")) {
                "ext1" -> server.ok(""""song":${songJson("ext1", "Lift", duration = 227)}""")
                else -> server.ok(""""song":${songJson("ext2", "Nude", duration = 255)}""")
            }
        }
        val library = LibraryIndex(songs = listOf(Song("lib1", "Karma Police", artist = "Artist", duration = 264)), albums = emptyList(), artists = emptyList())
        val lengths = RealLengths()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val model = SearchModel(server.connection(listOf("octoAcquisitions:1")), { library }, { emptyList() }, scope, lengths = lengths)
        model.type("radiohead")
        withTimeout(5_000) { while (lengths.corrected.value.size < 2) delay(20) }
        assertEquals(mapOf("ext1" to 227_000L, "ext2" to 255_000L), lengths.corrected.value)
        assertEquals(listOf("ext1", "ext2"), (model.state as SearchState.Done).found.outside.songs.map { it.id })
        // The library's song was never asked about.
        assertEquals(setOf("ext1", "ext2"), server.calls.filter { it.url.pathSegments.last() == "getSong" }.map { it.url.queryParameter("id") }.toSet())
        scope.cancel()
    }
}
