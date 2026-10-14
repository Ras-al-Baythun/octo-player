package app.winters.octo.desktop.playlists

import app.winters.octo.playlists.M3uEntry
import app.winters.octo.playlists.parseM3u
import app.winters.octo.playlists.writeM3u
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Playlist files written from the desktop and read back into its library.
class PlaylistFilesTest {
    private val nightcall = Song("s1", "Nightcall", artist = "Kavinsky", duration = 258)
    private val genesis = Song("s2", "Genesis", artist = "Justice", duration = 234)
    private val untitled = Song("s3", "Untitled", duration = 0)

    @Test
    fun aSongWritesItsServerPathOrElseArtistAndTitle() {
        val text = writeM3u(m3uLinesOf(listOf(nightcall, genesis, untitled), mapOf("s1" to "Kavinsky/OutRun/01 - Nightcall.flac", "s2" to " ")))
        assertEquals(
            "#EXTM3U\n" +
                "#EXTINF:258,Kavinsky - Nightcall\nKavinsky/OutRun/01 - Nightcall.flac\n" +
                "#EXTINF:234,Justice - Genesis\nJustice - Genesis\n" +
                "#EXTINF:-1,Untitled\nUntitled\n",
            text,
        )
    }

    @Test
    fun whatTheDesktopWritesItReadsBackToTheSameSongs() {
        val library = listOf(nightcall, genesis, Song("s9", "Nightcall", artist = "London Grammar", duration = 260))
        val text = writeM3u(m3uLinesOf(listOf(genesis, nightcall, genesis), emptyMap()))
        val found = importPlaylist(text, "Road trip.m3u", library)
        assertEquals(listOf("s2", "s1", "s2"), found.songs.map { it.id })
        assertEquals("Road trip", found.report.name)
        assertEquals(3, found.report.matched)
        assertEquals(3, found.report.total)
    }

    @Test
    fun anImportSaysWhichLinesItCouldNotFind() {
        val text = "#EXTM3U\n#EXTINF:258,Kavinsky - Nightcall\nx.mp3\n#EXTINF:100,Nobody - Nothing\ny.mp3\nD:\\Music\\Justice - Genesis.flac\n"
        val found = importPlaylist(text, "C:\\Lists\\Mix.m3u8", listOf(nightcall, genesis))
        assertEquals(listOf("s1", "s2"), found.songs.map { it.id })
        assertEquals("Mix", found.report.name)
        assertEquals(listOf("Nobody - Nothing"), found.report.missed)
        assertEquals("Not found: Nobody - Nothing", missedDetail(found.report.missed))
    }

    @Test
    fun anEmptyFileImportsNothing() {
        val found = importPlaylist("#EXTM3U\n", "Empty.m3u", listOf(nightcall))
        assertEquals(0, found.report.total)
        assertEquals(emptyList<Song>(), found.songs)
    }

    @Test
    fun theDetailsListAFewMissedLinesAndCountTheRest() {
        assertNull(missedDetail(emptyList()))
        assertEquals("Not found: a, b, c and 2 more", missedDetail(listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun anEntryIsLookedForByItsExtinfOrElseItsFileName() {
        assertEquals("Kavinsky" to "Nightcall", M3uEntry("x.mp3", 258, "Kavinsky", "Nightcall").lookedFor())
        assertEquals("Justice" to "Genesis", M3uEntry("D:\\Music\\03 - Justice - Genesis.flac").lookedFor())
        assertEquals("" to "Genesis", M3uEntry("Genesis.flac").lookedFor())
        assertNull(M3uEntry("").lookedFor())
    }

    @Test
    fun theBestFindIsTheSameRecordingElseAnyVersionElseTheSameTitle() {
        val live = Song("o1", "Nightcall (Live)", artist = "Kavinsky", isExternal = true)
        val studio = Song("o2", "Nightcall", artist = "Kavinsky", isExternal = true)
        val cover = Song("o3", "Nightcall", artist = "London Grammar", isExternal = true)
        assertEquals("o2", bestFind(listOf(cover, live, studio), "Kavinsky", "Nightcall")?.id)
        assertEquals("o1", bestFind(listOf(cover, live), "Kavinsky", "Nightcall")?.id)
        assertEquals("o3", bestFind(listOf(cover), "", "Nightcall")?.id)
        assertNull(bestFind(listOf(cover), "Kavinsky", "Nightcall"))
    }

    @Test
    fun songsFoundOnlineTakeTheMissedLinesPlaces() {
        val text = "#EXTM3U\n#EXTINF:234,Justice - Genesis\nx.mp3\n#EXTINF:100,Nobody - Nothing\ny.mp3\n#EXTINF:258,Kavinsky - Nightcall\nz.mp3\n"
        val read = importPlaylist(text, "Mixed.m3u", listOf(nightcall, genesis))
        assertEquals(listOf("s2", "s1"), read.songs.map { it.id })
        val nothing = read.match!!.missed.single()
        val online = Song("o9", "Nothing", artist = "Nobody", isExternal = true)
        val songs = withFinds(read.entries, read.match!!, read.songs, mapOf(nothing to online))
        assertEquals(listOf("s2", "o9", "s1"), songs.map { it.id })
        val report = read.report.withFound(mapOf(nothing to online), read.match!!)
        assertEquals(3, report.matched)
        assertEquals(1, report.found)
        assertEquals(emptyList<String>(), report.missed)
        assertEquals(3, parseM3u(text).size)
    }
}
