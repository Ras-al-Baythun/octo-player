package app.winters.octo.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayTest {
    @Test
    fun itIsTimeOnceTheQueueRunsShortWithRepeatOff() {
        val off = Player.REPEAT_MODE_OFF
        assertTrue("a single song", autoplayDue(true, off, upcoming = 0, ahead = 3))
        assertTrue("the third from the end", autoplayDue(true, off, upcoming = 2, ahead = 1))
        assertFalse(autoplayDue(true, off, upcoming = 3, ahead = 3))
        assertTrue("five saved ahead", autoplayDue(true, off, upcoming = 4, ahead = 5))
        assertFalse(autoplayDue(true, Player.REPEAT_MODE_ALL, upcoming = 0, ahead = 3))
        assertFalse(autoplayDue(true, Player.REPEAT_MODE_ONE, upcoming = 0, ahead = 3))
        // Switched off: never.
        assertFalse(autoplayDue(false, off, upcoming = 0, ahead = 3))
    }
}
