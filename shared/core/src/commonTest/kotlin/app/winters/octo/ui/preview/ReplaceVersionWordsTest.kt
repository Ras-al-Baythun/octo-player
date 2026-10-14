package app.winters.octo.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplaceVersionWordsTest {
    @Test
    fun theQuestionNamesBothAndHowLongTheOldFileIsKept() {
        assertEquals("Lift is replaced by Lift (Live). The file you have now goes to the trash for 30 days.", replaceVersionBody("Lift", "Lift (Live)", 30))
        assertEquals("Lift is replaced by Lift (Live). The file you have now goes to the trash for 1 day.", replaceVersionBody("Lift", "Lift (Live)", 1))
        assertEquals("The song is replaced by the chosen video. The file you have now goes to the trash until someone clears it.", replaceVersionBody("", " ", 0))
    }

    @Test
    fun theLinesAfter() {
        assertEquals("Fetching Lift (Live) for Lift", fetchingVersionLine("Lift", "Lift (Live)"))
        assertEquals("Fetching the chosen video for The song", fetchingVersionLine("", ""))
        assertEquals("Queue is full.", replaceRefusedLine("skipped", "Queue is full."))
        assertEquals("Couldn't replace the file (failed).", replaceRefusedLine("failed", " "))
        assertEquals("Couldn't replace the file (no answer).", replaceRefusedLine("", null))
    }
}
