package app.winters.octo.desktop.offline

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.audio.ServerSongs
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SongsAheadTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val songs = (1..5).map { Song("s$it", "Song $it", duration = 100, suffix = "mp3") }

    @Test
    fun onlyServerSongsWithALengthAndNoKeptCopyAreSaved() {
        val next = listOf(
            Song("local:/music/a.flac", "Here", duration = 100),
            Song("file:/tmp/b.mp3", "Opened", duration = 100),
            Song("stream", "A stream"),
            Song("kept", "Kept", duration = 100),
            Song("s1", "Song", duration = 100),
            Song("s1", "Song again", duration = 100),
        )
        assertEquals(listOf("s1"), songsToSave(next) { it == "kept" }.map { it.id })
    }

    @Test
    fun songsLongerThanTheLimitAreLeftToStream() {
        val next = listOf(
            Song("short", "Song", duration = 210),
            Song("mix", "Ba Sing Se Ambience", duration = 4706),
            Song("eight", "Long remix", duration = 8 * 60),
        )
        assertEquals(listOf("short", "eight"), songsToSave(next, maxMinutes = 8) { false }.map { it.id })
        assertEquals(listOf("short", "mix", "eight"), songsToSave(next, maxMinutes = 0) { false }.map { it.id })
    }

    @Test
    fun theNextSongsAreSavedAndTheRestTidiedAway() = runBlocking {
        FakeServer().use { server ->
            server.fileBy("stream") { request -> "audio of ${request.url.queryParameter("id")}".toByteArray() }
            val client = server.connection().client
            val folder = temp.newFolder("ahead")
            val ahead = SongsAhead(folder, ServerSongs(client = { client })::addressOf, { "mp3" }, { OkHttpClient() })
            val player = SilentPlayer()
            var saved = 0
            var count = 2
            player.play(songs, 0)
            val following = launch(Dispatchers.Default) { ahead.follow(player, { count }, { false }) { saved++ } }
            withTimeout(5_000) { while (saved < 2) yield() }
            assertEquals("audio of s2", ahead.fileFor("s2")!!.readText())
            assertEquals("audio of s3", ahead.fileFor("s3")!!.readText())
            assertNull(ahead.fileFor("s4"))
            // Moved on, one ahead: only the next song is kept, the rest go.
            count = 1
            player.play(songs, 3)
            withTimeout(5_000) { while (ahead.fileFor("s5") == null) yield() }
            withTimeout(5_000) { while (ahead.fileFor("s2") != null) yield() }
            assertEquals(setOf("s5.mp3"), folder.list()!!.toSet())
            following.cancel()
        }
    }
}
