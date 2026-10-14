package app.winters.octo.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.winters.octo.design.Corner
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.DesktopType
import app.winters.octo.design.Glyph
import app.winters.octo.design.IconSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PopupGap
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.preview.CHOOSE_VERSION_TITLE
import app.winters.octo.desktop.preview.LOOKING_FOR_VERSIONS
import app.winters.octo.desktop.preview.NO_VERSIONS
import app.winters.octo.desktop.preview.replaceBody
import app.winters.octo.ui.preview.REPLACE_VERSION_TITLE
import app.winters.octo.desktop.preview.choiceLine
import app.winters.octo.subsonic.PreviewChoice
import app.winters.octo.subsonic.Song

// "Choose version…" for a song found online: the videos it could play
// from, in the server's order (YouTube Music's own audio first), each with
// who put it up, how long it runs and where it was found; the one it plays
// from now is ticked. A click picks one and shuts the dialog, and the
// notice line says once the server took it. Escape or a click off the card
// picks nothing. Only on a server that offers the choice (`previews`).
// For a library song (not `outside`, or one the server says is in the
// library) a pick swaps the file for the video's download, so it is asked
// about first.
fun AppState.chooseVersion(song: Song, outside: Boolean = true) {
    val model = previews ?: return
    model.load(song)
    popups.showCentred(width = 460.dp, scrim = true) { close ->
        DisposableEffect(Unit) { onDispose { model.forget() } }
        val listed by model.choices.collectAsState()
        val problem by model.problem.collectAsState()
        val busy by model.busy.collectAsState()
        val choices = listed?.takeIf { it.id == song.id }?.choice
        val library = !outside || listed?.library == true
        VersionTitle(song)
        val line = when {
            problem != null -> problem
            choices == null -> LOOKING_FOR_VERSIONS
            choices.isEmpty() -> NO_VERSIONS
            else -> null
        }
        if (line != null) {
            PopupPadding { Txt(line, DesktopType.meta, if (problem != null) OctoColors.SignalOrange else OctoColors.TextMuted, maxLines = 3) }
        }
        choices?.forEach { choice ->
            ChoiceRow(choice, enabled = !busy) {
                close()
                if (library) askToReplace(song, choice) else model.choose(song, choice)
            }
        }
        PopupGap()
    }
}

// Asks before a library song's file is swapped for a video's download,
// then asks the server for it.
private fun AppState.askToReplace(song: Song, choice: PreviewChoice) {
    val model = previews ?: return
    val keepDays = health.actions?.keepDays ?: 0
    popups.showCentred { close ->
        MenuTitle(REPLACE_VERSION_TITLE)
        PopupPadding {
            Txt(replaceBody(song, choice, keepDays), DesktopType.body, OctoColors.TextPrimary, maxLines = 8)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End)) {
                GlazeCapsule(null, "Cancel", close)
                GlazeCapsule(OctoIcons.Playback, "Replace", {
                    close()
                    model.replace(song, choice)
                }, lit = true)
            }
        }
    }
}

// The dialog's heading and, under it, the song it is about.
@Composable
private fun VersionTitle(song: Song) {
    Column(
        Modifier.fillMaxWidth().padding(start = Space.L, end = Space.L, top = Space.M, bottom = Space.S),
        verticalArrangement = Arrangement.spacedBy(Space.Xxs),
    ) {
        Txt(CHOOSE_VERSION_TITLE, DesktopType.emphasis, OctoColors.TextPrimary, Modifier.semantics { heading() }, maxLines = Int.MAX_VALUE)
        val song = listOfNotNull(song.title.takeIf { it.isNotBlank() }, song.displayArtist ?: song.artist).joinToString(" · ")
        if (song.isNotBlank()) Txt(song, DesktopType.body, OctoColors.TextSecondary, maxLines = 2)
    }
}

// One video: its title over who put it up and its length, where it was
// found at the side, and a tick on the one the song plays from now.
@Composable
private fun ChoiceRow(choice: PreviewChoice, enabled: Boolean, pick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.S)
            .hoverLift(RoundedCornerShape(Corner.ArtS), clickable = enabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = pick)
            .semantics { if (choice.current) selected = true }
            .padding(horizontal = Space.S, vertical = Space.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Txt(choice.title.ifBlank { choice.videoId }, DesktopType.body, if (enabled) OctoColors.TextPrimary else OctoColors.TextMuted)
            choiceLine(choice).takeIf { it.isNotEmpty() }?.let { Txt(it, DesktopType.meta, OctoColors.TextMuted) }
        }
        if (choice.source.isNotBlank()) SourceLabel(choice.source)
        if (choice.current) Glyph(OctoIcons.Check, size = IconSize.Inline, tint = OctoColors.TextPrimary)
    }
}

// Where a video was found, in a small quiet pill.
@Composable
private fun SourceLabel(source: String) {
    Txt(
        source,
        DesktopType.label,
        OctoColors.TextMuted,
        Modifier.clip(RoundedCornerShape(50)).background(Pill).padding(horizontal = Space.Xs, vertical = 2.dp),
    )
}

private val Pill = Color.White.copy(alpha = 0.06f)
