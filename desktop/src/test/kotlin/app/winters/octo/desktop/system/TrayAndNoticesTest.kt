package app.winters.octo.desktop.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayAndNoticesTest {
    private fun now(key: Long = 1, playing: Boolean = true, canNext: Boolean = true, title: String = "Title", artist: String = "Artist") =
        NowPlaying(key, "s$key", title, artist, "Album", "", 200_000, null, null, null, emptyList(), playing, canPrevious = true, canNext = canNext)

    private fun actions(menu: List<TrayEntry>) = menu.filterIsInstance<TrayEntry.Action>().associateBy { it.action }

    @Test
    fun theMenuNamesTheSongAndOffersTheTransport() {
        val menu = trayMenu(now(), miniPlayerOpen = false)
        assertEquals(TrayEntry.Heading("Title · Artist"), menu.first())
        val byAction = actions(menu)
        assertEquals("Pause", byAction.getValue(TrayAction.PlayPause).label)
        assertEquals(true, byAction.getValue(TrayAction.Next).enabled)
        assertEquals("Show Octo", byAction.getValue(TrayAction.ShowWindow).label)
        assertEquals("Mini player", byAction.getValue(TrayAction.MiniPlayer).label)
        assertEquals("Quit Octo", byAction.getValue(TrayAction.Quit).label)
        assertEquals("the order of the menu", listOf(TrayAction.PlayPause, TrayAction.Next, TrayAction.Previous, TrayAction.ShowWindow, TrayAction.MiniPlayer, TrayAction.Quit), menu.filterIsInstance<TrayEntry.Action>().map { it.action })
    }

    @Test
    fun withNothingPlayingTheTransportIsOff() {
        val menu = trayMenu(null, miniPlayerOpen = true)
        assertEquals(TrayEntry.Heading("Nothing playing"), menu.first())
        val byAction = actions(menu)
        assertEquals("Play", byAction.getValue(TrayAction.PlayPause).label)
        listOf(TrayAction.PlayPause, TrayAction.Next, TrayAction.Previous).forEach { assertEquals(false, byAction.getValue(it).enabled) }
        assertEquals(true, byAction.getValue(TrayAction.Quit).enabled)
        assertEquals("Close the mini player", byAction.getValue(TrayAction.MiniPlayer).label)
    }

    @Test
    fun pausedOffersPlayAndTheLastSongHasNoNext() {
        val byAction = actions(trayMenu(now(playing = false, canNext = false), miniPlayerOpen = false))
        assertEquals("Play", byAction.getValue(TrayAction.PlayPause).label)
        assertEquals(false, byAction.getValue(TrayAction.Next).enabled)
        assertEquals(true, byAction.getValue(TrayAction.Previous).enabled)
    }

    @Test
    fun longNamesAreCut() {
        val heading = trayMenu(now(title = "A".repeat(80), artist = ""), false).first() as TrayEntry.Heading
        assertEquals(48, heading.text.length)
        assertEquals('…', heading.text.last())
        assertEquals("Octo", trayTooltip(null))
        assertEquals("Octo: Title · Artist", trayTooltip(now()))
    }

    @Test
    fun aNoticeComesOncePerSongWhilePlayingAndOnlyWhenWanted() {
        val notices = NowPlayingNotices()
        assertEquals("Title" to "Artist · Album", notices.noticeFor(now(1), enabled = true, windowInFront = false))
        assertNull("the same song again", notices.noticeFor(now(1), enabled = true, windowInFront = false))
        assertNull("a paused song", notices.noticeFor(now(2, playing = false), enabled = true, windowInFront = false))
        assertEquals("it plays now", "Title" to "Artist · Album", notices.noticeFor(now(2), enabled = true, windowInFront = false))
        assertNull("the window is in front", notices.noticeFor(now(3), enabled = true, windowInFront = true))
        assertNull("that song was seen", notices.noticeFor(now(3), enabled = true, windowInFront = false))
        assertNull("turned off", notices.noticeFor(now(4), enabled = false, windowInFront = false))
        assertNull(notices.noticeFor(null, enabled = true, windowInFront = false))
        assertEquals("Title" to "Album", notices.noticeFor(now(5, artist = ""), enabled = true, windowInFront = false))
    }

    @Test
    fun theTrayIconGoesBackInWhenTheTrayComesBack() {
        var supported = true
        var adds = 0
        var removes = 0
        val keeper = TrayKeeper({ supported }, { adds++ }, { removes++ })
        keeper.sync()
        keeper.sync()
        assertEquals("put in once", 1, adds)
        assertTrue(keeper.added)
        supported = false
        keeper.sync()
        assertFalse("the tray went away", keeper.added)
        supported = true
        keeper.sync()
        assertEquals("back in", 2, adds)
        keeper.drop()
        assertEquals(1, removes)
        assertFalse(keeper.added)
    }

    @Test
    fun nothingTheTrayThrowsGetsOut() {
        var adds = 0
        val keeper = TrayKeeper({ true }, { adds++; throw UnsupportedOperationException("no tray") }, { throw UnsupportedOperationException("no tray") })
        keeper.sync()
        assertFalse(keeper.added)
        keeper.sync()
        assertEquals("tried again", 2, adds)
        TrayKeeper({ throw UnsupportedOperationException("no tray") }, {}, {}).sync()
        val added = TrayKeeper({ true }, {}, { throw IllegalStateException("gone") }).apply { sync() }
        added.drop()
        assertFalse(added.added)
    }

    @Test
    fun theLibrarysEventNumbersBecomeEvents() {
        assertEquals(
            listOf(SystemEvent.Play, SystemEvent.Pause, SystemEvent.Toggle, SystemEvent.Next, SystemEvent.Previous, SystemEvent.Stop, SystemEvent.SeekTo(1234), SystemEvent.Sleep, SystemEvent.Wake),
            (1..9).map { systemEventOf(it, 1234) },
        )
        assertNull(systemEventOf(0, 0))
        assertEquals(SystemEvent.SeekTo(0), systemEventOf(7, -9))
    }
}
