package app.winters.octo.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnAlbumsTest {
    @Test
    fun anAlbumIsOwnWithMostOfItOrChosen() {
        assertFalse(isOwnAlbum(1, chosenAsAlbum = false))
        assertFalse(isOwnAlbum(2, chosenAsAlbum = false))
        assertTrue(isOwnAlbum(3, chosenAsAlbum = false))
        assertTrue(isOwnAlbum(1, chosenAsAlbum = true))
    }

    @Test
    fun recentPlaysShowSongsOfOtherAlbumsAsSongs() {
        val plays = listOf(
            "s1" to "single",
            "s2" to "full",
            "s3" to "full",
            "s1" to "single",
            "s4" to null,
        )
        val got = recentPlays(plays, own = { it == "full" }, keyOfAlbum = { it }, keyOfSong = { it }, limit = 10)
        assertEquals(
            listOf<RecentPlay<String, String>>(RecentPlay.Song("s1"), RecentPlay.Album("full"), RecentPlay.Song("s4")),
            got,
        )
        assertEquals(2, recentPlays(plays, { true }, { it }, { it }, limit = 2).size)
    }
}
