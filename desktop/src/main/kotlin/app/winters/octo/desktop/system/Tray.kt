package app.winters.octo.desktop.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import kotlinx.coroutines.delay
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon

// What the tray icon's menu (the menu bar's, on macOS) can do.
enum class TrayAction { PlayPause, Next, Previous, ShowWindow, MiniPlayer, Quit }

// One line of the tray menu.
sealed interface TrayEntry {
    // The song playing, shown and not clickable.
    data class Heading(val text: String) : TrayEntry

    data class Action(val action: TrayAction, val label: String, val enabled: Boolean = true) : TrayEntry

    data object Divider : TrayEntry
}

// Menu labels are cut here so a long title keeps the menu a sensible width.
private const val LABEL_LIMIT = 48

fun shortLabel(text: String, limit: Int = LABEL_LIMIT): String =
    if (text.length <= limit) text else text.take(limit - 1).trimEnd() + "…"

// The line naming the song: its title, and its artist when known.
fun songLine(now: NowPlaying): String = if (now.artist.isBlank()) now.title else "${now.title} · ${now.artist}"

// The tray menu for what is playing: the song, the transport, then the
// window, the mini player and Quit.
fun trayMenu(now: NowPlaying?, miniPlayerOpen: Boolean): List<TrayEntry> = listOf(
    TrayEntry.Heading(now?.let { shortLabel(songLine(it)) } ?: "Nothing playing"),
    TrayEntry.Divider,
    TrayEntry.Action(TrayAction.PlayPause, if (now?.playing == true) "Pause" else "Play", enabled = now != null),
    TrayEntry.Action(TrayAction.Next, "Next", enabled = now?.canNext == true),
    TrayEntry.Action(TrayAction.Previous, "Previous", enabled = now != null),
    TrayEntry.Divider,
    TrayEntry.Action(TrayAction.ShowWindow, "Show Octo"),
    TrayEntry.Action(TrayAction.MiniPlayer, if (miniPlayerOpen) "Close the mini player" else "Mini player"),
    TrayEntry.Divider,
    TrayEntry.Action(TrayAction.Quit, "Quit Octo"),
)

// The words shown when the pointer rests on the tray icon.
fun trayTooltip(now: NowPlaying?): String = now?.let { "Octo: " + shortLabel(songLine(it), 100) } ?: "Octo"

// How often the guarded tray looks whether the system tray is there.
private const val TRAY_CHECK_MS = 3_000L

// The tray icon's size in pixels on Linux, as Compose's own tray draws it.
private const val TRAY_ICON_PX = 22f

// Keeps the icon in the system tray while the tray comes and goes. On Linux
// the desktop's tray can vanish for a moment (GNOME reloading its
// AppIndicator extension), and AWT then throws on every tray call; here
// nothing it throws gets out, and the icon goes back in once the tray is back.
class TrayKeeper(
    private val isSupported: () -> Boolean,
    private val add: () -> Unit,
    private val remove: () -> Unit,
) {
    var added = false
        private set

    fun sync() {
        if (!runCatching(isSupported).getOrDefault(false)) {
            added = false
            return
        }
        if (!added) added = runCatching(add).isSuccess
    }

    fun drop() {
        if (added) runCatching(remove)
        added = false
    }
}

// The AWT side of the guarded tray. The icon itself is made only once the
// tray is there, since AWT refuses to make one while there is none.
private class AwtTray(private val onAction: (TrayAction) -> Unit) {
    private val menu = PopupMenu()
    private var icon: TrayIcon? = null
    private var image: Image? = null
    private var tooltip = ""
    private var entries: List<TrayEntry> = emptyList()

    val keeper = TrayKeeper(SystemTray::isSupported, ::add, ::remove)

    fun show(image: Image, tooltip: String, entries: List<TrayEntry>) {
        this.image = image
        this.tooltip = tooltip
        if (entries != this.entries) {
            this.entries = entries
            runCatching { fill() }
        }
        icon?.let {
            runCatching {
                if (it.image !== image) it.image = image
                if (it.toolTip != tooltip) it.toolTip = tooltip
            }
        }
    }

    private fun add() {
        val made = icon ?: TrayIcon(checkNotNull(image), tooltip, menu).apply {
            isImageAutoSize = true
            addActionListener { onAction(TrayAction.ShowWindow) }
        }.also { icon = it }
        val tray = SystemTray.getSystemTray()
        // Taken out first: after the tray went away AWT may still count it in.
        runCatching { tray.remove(made) }
        tray.add(made)
    }

    private fun remove() {
        icon?.let { SystemTray.getSystemTray().remove(it) }
    }

    private fun fill() {
        menu.removeAll()
        entries.forEach { entry ->
            when (entry) {
                is TrayEntry.Heading -> menu.add(MenuItem(entry.text).apply { isEnabled = false })
                is TrayEntry.Action -> menu.add(MenuItem(entry.label).apply {
                    isEnabled = entry.enabled
                    addActionListener { onAction(entry.action) }
                })
                TrayEntry.Divider -> menu.addSeparator()
            }
        }
    }
}

// The tray icon on Linux, kept by hand rather than by Compose's Tray, whose
// clean-up throws once the system tray is gone and takes the whole app down.
@Composable
private fun GuardedTray(icon: Painter, now: NowPlaying?, miniPlayerOpen: Boolean, onAction: (TrayAction) -> Unit) {
    val act by rememberUpdatedState(onAction)
    val tray = remember { AwtTray { act(it) } }
    val density = LocalDensity.current
    val image = remember(icon, density) { icon.toAwtImage(density, LayoutDirection.Ltr, Size(TRAY_ICON_PX, TRAY_ICON_PX)) }
    val entries = trayMenu(now, miniPlayerOpen)
    val tooltip = trayTooltip(now)
    SideEffect { tray.show(image, tooltip, entries) }
    LaunchedEffect(tray) {
        while (true) {
            tray.keeper.sync()
            delay(TRAY_CHECK_MS)
        }
    }
    DisposableEffect(tray) { onDispose { tray.keeper.drop() } }
}

// The tray icon, drawn from the menu above. A click on the icon itself
// brings the window forward. Linux gets the guarded tray, since its tray
// can come and go while Octo runs.
@Composable
fun ApplicationScope.OctoTray(
    icon: Painter,
    state: TrayState,
    now: NowPlaying?,
    miniPlayerOpen: Boolean,
    guarded: Boolean,
    onAction: (TrayAction) -> Unit,
) {
    if (guarded) return GuardedTray(icon, now, miniPlayerOpen, onAction)
    Tray(
        icon = icon,
        state = state,
        tooltip = trayTooltip(now),
        onAction = { onAction(TrayAction.ShowWindow) },
        menu = {
            trayMenu(now, miniPlayerOpen).forEach { entry ->
                when (entry) {
                    is TrayEntry.Heading -> Item(entry.text, enabled = false, onClick = {})
                    is TrayEntry.Action -> Item(entry.label, enabled = entry.enabled, onClick = { onAction(entry.action) })
                    TrayEntry.Divider -> Separator()
                }
            }
        },
    )
}
