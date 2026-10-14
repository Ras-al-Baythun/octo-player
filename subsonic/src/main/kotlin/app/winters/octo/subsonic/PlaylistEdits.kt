package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The OpenSubsonic extension a server lists when it takes a call's params
// in a form body, so a long list of songs fits in one call.
const val FORM_POST_EXTENSION = "formPost"

// The OpenSubsonic extension an Octo server lists when it keeps playlists
// of its own, which take songs found online. Navidrome's own playlists
// refuse such a song (see refusesOutsideSong).
const val OCTO_PLAYLISTS = "octoPlaylists"

// Whether a playlist call failed because the playlist is Navidrome's own
// and a song found online was sent to it: code 70, naming an Octo playlist
// as what it would take.
fun SubsonicException.refusesOutsideSong(): Boolean =
    this is SubsonicException.NotFound && message.orEmpty().contains("Octo playlist")

// How many song ids go in one call when they must fit in the address. 150
// ids come to about 5 KB, under the 8 KB most servers and proxies allow.
const val PLAYLIST_SONGS_PER_CALL = 150

// One call that changes a playlist. A param name may repeat.
data class PlaylistCall(val endpoint: String, val params: List<Pair<String, String>>)

// The songs split into the calls that carry them. With a form body they all
// go at once; otherwise in batches. There is always at least one batch, so
// an empty list still makes its call.
fun songBatches(songIds: List<String>, formPost: Boolean, perCall: Int = PLAYLIST_SONGS_PER_CALL): List<List<String>> =
    if (formPost || songIds.size <= perCall) listOf(songIds) else songIds.chunked(perCall)

// The calls that make a playlist's songs exactly these, in this order.
// createPlaylist with a playlistId replaces the whole list; batches after
// the first are added to the end with updatePlaylist.
fun replaceSongsCalls(
    playlistId: String,
    songIds: List<String>,
    formPost: Boolean,
    perCall: Int = PLAYLIST_SONGS_PER_CALL,
): List<PlaylistCall> {
    val batches = songBatches(songIds, formPost, perCall)
    val first = PlaylistCall("createPlaylist", listOf("playlistId" to playlistId) + batches.first().map { "songId" to it })
    return listOf(first) + addSongsCalls(playlistId, batches.drop(1))
}

// The calls that make a new playlist: the first batch goes with the name,
// and the rest are added once the server says the new id. `octo` asks an
// Octo server for one of its own playlists, which takes songs found online.
fun createCalls(
    name: String,
    songIds: List<String>,
    formPost: Boolean,
    perCall: Int = PLAYLIST_SONGS_PER_CALL,
    octo: Boolean = false,
): Pair<PlaylistCall, List<List<String>>> {
    val batches = songBatches(songIds, formPost, perCall)
    val params = listOf("name" to name) + (if (octo) listOf("octo" to "true") else emptyList()) + batches.first().map { "songId" to it }
    return PlaylistCall("createPlaylist", params) to batches.drop(1)
}

// One updatePlaylist call per batch, each adding its songs to the end.
fun addSongsCalls(playlistId: String, batches: List<List<String>>): List<PlaylistCall> =
    batches.filter { it.isNotEmpty() }.map { batch ->
        PlaylistCall("updatePlaylist", listOf("playlistId" to playlistId) + batch.map { "songIdToAdd" to it })
    }

// The calls for updatePlaylist. Removals go by position in the list as it is
// before the call, and may repeat; added songs go to the end. Songs past the
// first batch follow in calls of their own.
fun updateCalls(
    playlistId: String,
    name: String?,
    comment: String?,
    public: Boolean?,
    songIdsToAdd: List<String>,
    songIndexesToRemove: List<Int>,
    formPost: Boolean,
    perCall: Int = PLAYLIST_SONGS_PER_CALL,
): List<PlaylistCall> {
    val batches = songBatches(songIdsToAdd, formPost, perCall)
    val params = buildList {
        add("playlistId" to playlistId)
        name?.let { add("name" to it) }
        comment?.let { add("comment" to it) }
        public?.let { add("public" to "$it") }
        songIndexesToRemove.forEach { add("songIndexToRemove" to "$it") }
        batches.first().forEach { add("songIdToAdd" to it) }
    }
    return listOf(PlaylistCall("updatePlaylist", params)) + addSongsCalls(playlistId, batches.drop(1))
}

// createPlaylist's answer. Servers since API 1.14 send the playlist back;
// older ones send nothing.
@Serializable
internal data class CreatedPlaylist(val playlist: PlaylistWithSongs? = null)
