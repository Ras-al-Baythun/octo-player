package app.winters.octo.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayPicksTest {
    @Test
    fun theServersSimilarSongsComeFirstInItsOrder() {
        val picks = autoplayPicks(
            similar = listOf("s1", "s2", "s3"),
            sameArtist = listOf("a1"),
            sameGenre = listOf("g1"),
            exclude = emptySet(),
        )
        assertEquals(listOf("s1", "s2", "s3"), picks)
    }

    @Test
    fun recentPlaysAndTheQueueAreNeverPicked() {
        val picks = autoplayPicks(
            similar = listOf("s1", "s2", "s3", "s2"),
            sameArtist = emptyList(),
            sameGenre = emptyList(),
            exclude = setOf("s1"),
        )
        assertEquals(listOf("s2", "s3"), picks)
    }

    @Test
    fun withoutTheServerTheSameArtistComesBeforeTheSameGenre() {
        val picks = autoplayPicks(
            similar = emptyList(),
            sameArtist = listOf("a1", "a2", "seed"),
            sameGenre = listOf("a2", "g1", "g2"),
            exclude = setOf("seed"),
        )
        assertEquals(listOf("a1", "a2", "g1", "g2"), picks)
    }

    @Test
    fun whenTheServerOnlyOffersSongsJustPlayedTheLibraryFillsIn() {
        val picks = autoplayPicks(
            similar = listOf("s1"),
            sameArtist = listOf("a1"),
            sameGenre = emptyList(),
            exclude = setOf("s1"),
        )
        assertEquals(listOf("a1"), picks)
    }

    @Test
    fun addsOneBatchAtATime() {
        val picks = autoplayPicks(List(30) { "s$it" }, emptyList(), emptyList(), emptySet())
        assertEquals(AUTOPLAY_BATCH, picks.size)
        assertEquals("s0", picks.first())
    }

    @Test
    fun nothingToPickIsNothing() {
        assertTrue(autoplayPicks(emptyList(), listOf("seed"), emptyList(), setOf("seed")).isEmpty())
    }

    @Test
    fun itAddsOnceTheThirdSongFromTheEndIsOn() {
        assertTrue("a single song played", autoplayWanted(upcoming = 0, ahead = 3))
        assertTrue("the third from the end", autoplayWanted(upcoming = 2, ahead = 3))
        assertFalse("three still to come", autoplayWanted(upcoming = 3, ahead = 3))
        assertTrue("fewer saved ahead still keeps three ready", autoplayWanted(upcoming = 2, ahead = 1))
        assertTrue("more saved ahead keeps more ready", autoplayWanted(upcoming = 4, ahead = 5))
        assertFalse(autoplayWanted(upcoming = 5, ahead = 5))
    }
}
