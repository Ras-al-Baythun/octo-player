package app.winters.octo.desktop

import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.FakeSecrets
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.subsonic.OCTO_PREVIEW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Opening Octo signed in: the server is asked again what it offers, so an
// Octo updated since the last sign-in shows its new menus at once.
class StartupFactsTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun serverLists(vararg extensions: String) {
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[${extensions.joinToString(",") { """{"name":"$it","versions":[1]}""" }}]""", type = "octo")
        server.answer("getAlbumList2", """"albumList2":{}""")
        server.answer("getArtists", """"artists":{}""")
        server.answer("search3", """"searchResult3":{}""")
        server.answer("getPlaylists", """"playlists":{}""")
    }

    @Test
    fun aServerThatLearnedAnExtensionSinceTheSignInIsSeenOnOpening() {
        serverLists("octoAcquisitions")
        val settings = SettingsStore(File(temp.root, "settings.json"), 0)
        val http = OkHttpClient()
        val accounts = Accounts(settings, FakeSecrets(), http)
        runBlocking { accounts.signIn(server.address, "winters", "pw") }
        // Octo on the server was updated while the app was closed.
        serverLists("octoAcquisitions", OCTO_PREVIEW)
        val restored = accounts.restore()!!
        assertFalse("the saved facts are the old ones", restored.supports(OCTO_PREVIEW))
        val app = AppState(settings, accounts, http, scope, DesktopOs.Windows, SilentPlayer(), OnlineLyrics(http, server.address.toHttpUrl()), restored = restored, listeningRoot = temp.root)
        waitFor { app.previews != null }
        assertNotNull("Choose version is offered", app.previews)
        assertTrue(app.connection!!.supports(OCTO_PREVIEW))
        assertTrue("and the next opening knows it already", accounts.active!!.extensions.contains("$OCTO_PREVIEW:1"))
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
    }
}
