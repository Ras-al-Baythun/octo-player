package app.winters.octo.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.PreviewChoices
import app.winters.octo.data.VersionSheet
import app.winters.octo.design.GlassSheet
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Spinner
import app.winters.octo.discovery.asTrack
import app.winters.octo.subsonic.PreviewChoice
import app.winters.octo.ui.common.Artwork
import app.winters.octo.ui.common.LengthStyle
import app.winters.octo.ui.common.QuietButton
import app.winters.octo.ui.common.rowLength
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// The menu row that opens the sheet.
const val CHOOSE_VERSION = "Choose version…"

@HiltViewModel
class ChooseVersionViewModel @Inject constructor(val previews: PreviewChoices, online: OnlineDao) : ViewModel() {
    // The song the sheet is open for, shaped like a library song.
    @OptIn(ExperimentalCoroutinesApi::class)
    val song: StateFlow<TrackEntity?> = previews.view
        .flatMapLatest { view -> if (view == null) flowOf(null) else online.songFlow(view.trackId).map { it?.asTrack() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

// The Choose version sheet, over everything: the videos a song found
// online could play from, on the signed-in Octo server. A tap makes the
// song play from that video from then on. Opened from a song's menu.
@Composable
fun ChooseVersionHost(vm: ChooseVersionViewModel = hiltViewModel()) {
    val model = vm.previews
    val view by model.view.collectAsStateWithLifecycle()
    val song by vm.song.collectAsStateWithLifecycle()
    // Kept while the sheet fades out.
    var shown by remember { mutableStateOf<VersionSheet?>(null) }
    view?.let { shown = it }
    GlassSheet(visible = view != null, onDismiss = model::close) {
        shown?.let { ChooseVersion(model, it, song) }
    }
}

@Composable
private fun ColumnScope.ChooseVersion(model: PreviewChoices, view: VersionSheet, song: TrackEntity?) {
    val choices by model.choices.collectAsStateWithLifecycle()
    val loading by model.loading.collectAsStateWithLifecycle()
    val problem by model.problem.collectAsStateWithLifecycle()
    val choosing by model.choosing.collectAsStateWithLifecycle()
    Bar(onClose = model::close) {
        if (!loading) QuietButton("Search again", { model.load(view.serverId) })
    }
    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
        item(key = "head") { Head(song) }
        problem?.let { words ->
            item(key = "problem") { Text(words, style = OctoType.caption, color = OctoColors.SignalOrange, modifier = Modifier.padding(top = 12.dp)) }
        }
        item(key = "rule") { Rule() }
        when {
            loading -> item(key = "wait") { Text("Looking for videos", style = OctoType.caption, color = OctoColors.TextMuted) }
            choices.isEmpty() && problem == null -> item(key = "none") { Text("Nothing found for this song", style = OctoType.bodySmall, color = OctoColors.TextMuted) }
        }
        items(choices, key = { it.videoId }) { choice ->
            ChoiceLine(choice, sending = choosing == choice.videoId, enabled = choosing == null && !choice.current) {
                model.choose(view.serverId, choice)
            }
        }
    }
}

// The song at the top: its artwork, title and artist.
@Composable
private fun Head(song: TrackEntity?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(song?.artwork, 64.dp, shape = RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(CHOOSE_VERSION.trimEnd('…').uppercase(), style = OctoType.caption, color = OctoColors.TextMuted)
            Text(song?.title.orEmpty(), style = OctoType.headline, color = OctoColors.TextPrimary)
            song?.artist?.takeIf(String::isNotBlank)?.let { Text(it, style = OctoType.bodySmall, color = OctoColors.TextSecondary) }
        }
    }
    Text("The song plays from the video you pick, and is fetched from it when added to your library.", style = OctoType.caption, color = OctoColors.TextMuted, modifier = Modifier.padding(top = 12.dp))
}

// One video: its title, its channel, where it was found, its length, and
// a check on the one the song plays from now.
@Composable
private fun ChoiceLine(choice: PreviewChoice, sending: Boolean, enabled: Boolean, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onPick)
            .semantics { selected = choice.current }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(choice.title.ifBlank { choice.videoId }, style = OctoType.bodySmall, color = OctoColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Source(choice.source)
                choice.channel?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = OctoType.caption, color = OctoColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        choice.lengthSeconds?.let { seconds -> rowLength(seconds * 1_000L) }?.let { Text(it, style = LengthStyle, color = OctoColors.TextMuted) }
        when {
            sending -> Spinner(size = 18.dp, color = OctoColors.TextSecondary)
            choice.current -> Icon(painterResource(OctoIcons.Check), contentDescription = "Plays from this now", tint = OctoColors.TextPrimary, modifier = Modifier.size(18.dp))
            else -> Spacer(Modifier.size(18.dp))
        }
    }
}

// Where a video was found, on a small chip.
@Composable
private fun Source(text: String) {
    Text(
        text,
        style = OctoType.caption,
        color = OctoColors.TextSecondary,
        modifier = Modifier.clip(CircleShape).background(Chip).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

// The sheet's top line: close, then its actions.
@Composable
private fun Bar(onClose: () -> Unit, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose).semantics { contentDescription = "Close" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(OctoIcons.Close), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun Rule() {
    Box(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(Thread))
}

private val Chip = Color.White.copy(alpha = 0.08f)
private val Thread = Color.White.copy(alpha = 0.10f)
