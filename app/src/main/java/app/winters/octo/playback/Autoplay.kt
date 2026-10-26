package app.winters.octo.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.UserDao
import app.winters.octo.catalog.isFind
import app.winters.octo.catalog.searchKey
import app.winters.octo.discovery.asTrack
import app.winters.octo.offline.Prefetcher
import app.winters.octo.output.OutputSwitch
import app.winters.octo.player.PlayerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Songs played this recently are not picked again.
private const val RECENT_PLAYS = 50

// Whether it is time to add songs: Autoplay is on, repeat is off, and the
// queue is about to run short, with `upcoming` songs left after the one on
// and `ahead` songs to be saved ahead (see autoplayWanted).
fun autoplayDue(enabled: Boolean, repeatMode: Int, upcoming: Int, ahead: Int): Boolean =
    enabled && repeatMode == Player.REPEAT_MODE_OFF && autoplayWanted(upcoming, ahead)

// Keeps music playing when the queue runs out. Once the third song from the
// end is on (or more, when more are saved ahead), with repeat off, it adds
// songs like it, marked as Autoplay's so the queue can tell them from the
// listener's own; early, so they are saved ahead before a connection drops.
// The service attaches its player and calls `check` whenever the song, the
// queue, repeat or playing changes. Turning the setting off stops it adding.
@OptIn(UnstableApi::class)
@Singleton
class Autoplay @Inject constructor(
    private val settings: PlayerSettings,
    private val radio: LibraryRadio,
    private val catalog: CatalogDao,
    private val online: OnlineDao,
    private val user: UserDao,
    private val playable: PlayableSongs,
    private val prefetch: Prefetcher,
) {
    private var player: OutputSwitch? = null
    private var scope: CoroutineScope? = null
    private var enabled = false
    private var settingsJob: Job? = null

    // Adds after one entry at a time.
    private var watch: Job? = null
    private var watching: String? = null

    // The last song Autoplay added after, so it tries once per song.
    private var doneFor: String? = null

    fun attach(player: OutputSwitch, scope: CoroutineScope) {
        this.player = player
        this.scope = scope
        settingsJob = scope.launch {
            settings.prefs.map { it.autoplay }.distinctUntilChanged().collect { on ->
                enabled = on
                if (!on) removeAdded()
                check()
            }
        }
    }

    fun detach() {
        settingsJob?.cancel()
        stop()
        player = null
        scope = null
    }

    fun check() {
        val player = player ?: return
        val item = player.currentMediaItem
        val key = item?.entryId
        val due = key != null && key != doneFor && !isRadio(item.mediaId) &&
            autoplayDue(enabled, player.repeatMode, player.upcomingCount(), prefetch.aheadCount()) &&
            (player.isPlaying || player.playbackState == Player.STATE_ENDED)
        if (!due) {
            // Paused, repeat on, songs added, or switched off: stand down.
            stop()
            return
        }
        if (watch?.isActive == true && watching == key) return
        stop()
        watching = key
        watch = scope?.launch { add(player, key, item.mediaId) }
    }

    // Switched off: the songs Autoplay added and that have not played yet
    // go, so nothing it picked plays on.
    private fun removeAdded() {
        val player = player ?: return
        for (i in player.mediaItemCount - 1 downTo player.currentMediaItemIndex + 1) {
            if (player.getMediaItemAt(i).isAutoplay) player.removeMediaItem(i)
        }
    }

    private fun stop() {
        watch?.cancel()
        watch = null
        watching = null
    }

    private suspend fun add(player: OutputSwitch, key: String, seedId: String) {
        val entries = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        val queued = entries.map { it.mediaId }
        // The listener's own songs keep Autoplay near their taste.
        val anchors = entries.takeLast(30).filterNot { it.isAutoplay }.map { it.mediaId }.filter { it != seedId }.takeLast(10).reversed()
        val items = playable.items(picksAfter(seedId, queued, anchors)).map { it.markedAutoplay() }
        // Tried once for this song, whatever came of it.
        doneFor = key
        if (items.isEmpty()) {
            Log.i("Octo", "autoplay: nothing to add")
            return
        }
        // Only if the song is still on, the queue still short and the setting still on.
        if (player.currentMediaItem?.entryId != key ||
            !autoplayDue(enabled, player.repeatMode, player.upcomingCount(), prefetch.aheadCount())
        ) return
        val first = player.mediaItemCount
        player.addMediaItems(first, items)
        // The song already ended while the server answered: carry on.
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(first, 0)
            player.play()
        }
        Log.i("Octo", "autoplay: added ${items.size}")
    }

    // Octo's radio for the seed and the listener's own recent songs
    // (`anchors`), from the library and the server's suggestions; else the
    // library's songs by the same artist, then in the same genre.
    private suspend fun picksAfter(seedId: String, queued: List<String>, anchors: List<String>): List<String> {
        val seed = seedTrack(seedId) ?: return emptyList()
        val recent = user.playedTracks().first().sortedByDescending { it.lastPlayedAt }.take(RECENT_PLAYS).map { it.track.id }
        val exclude = (queued + recent + seed.id).toSet()
        val own = catalog.tracksByIdsUnordered(anchors).associateBy { it.id }
        val seeds = listOf(seed) + anchors.mapNotNull(own::get)
        val mixed = radio.songsLike(seeds, AUTOPLAY_BATCH, exclude, before = queued.takeLast(30))
        if (mixed.isNotEmpty()) return mixed
        return autoplayPicks(emptyList(), sameArtist(seed).shuffled(), sameGenre(seed).shuffled(), exclude)
    }

    // The song as the library has it, or as found online.
    private suspend fun seedTrack(id: String): TrackEntity? =
        if (isFind(id)) online.byIds(listOf(id)).firstOrNull()?.asTrack() else catalog.track(id)

    // The library's songs by the seed's artist. A song found online is
    // matched to a library artist by name.
    private suspend fun sameArtist(seed: TrackEntity): List<String> {
        val artistIds = if (isFind(seed.id)) catalog.artistsWithKeys(listOf(searchKey(seed.artist))).map { it.id } else listOf(seed.artistId)
        return artistIds.flatMap { catalog.artistAlbums(it).first() }.flatMap { catalog.albumTrackIds(it.id) }
    }

    private suspend fun sameGenre(seed: TrackEntity): List<String> =
        if (seed.genre.isBlank()) emptyList() else catalog.genreTrackIds(seed.genre)

    // How many songs play after the one on, in play order, counted only as
    // far as Autoplay could want.
    private fun OutputSwitch.upcomingCount(): Int {
        val timeline = currentTimeline
        if (timeline.isEmpty) return 0
        val most = maxOf(AUTOPLAY_MIN_AHEAD, prefetch.aheadCount())
        var index = currentMediaItemIndex
        var count = 0
        while (count < most) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            count++
        }
        return count
    }
}
