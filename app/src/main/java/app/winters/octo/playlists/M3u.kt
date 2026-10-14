package app.winters.octo.playlists

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.sameRecording
import app.winters.octo.discovery.sameSongAnyVersion

// Reading and writing playlist files is shared with the desktop
// (shared/core M3uText.kt); the phone matches against its own catalog, and
// looks for what the catalog lacks online when the server keeps such songs
// in its playlists.

// Finds each playlist entry among the phone's library songs; see the shared
// matchM3u for the order it tries.
fun matchM3u(entries: List<M3uEntry>, library: List<TrackEntity>, paths: Map<String, String>): M3uMatch =
    matchM3u(entries, library.map { M3uSong(it.id, it.title, it.artist, it.durationMs) }, paths)

// Leading track numbers on a file name, like "01 ", "01 - " or "1. ".
private val TrackNumber = Regex("""^\d{1,3}\s*[-.)_]?\s+""")

// What an entry says of its song, for looking it up online: the #EXTINF
// artist and title, else the file's name read as "Artist - Title" or as a
// title alone. The artist is empty when nothing names one; null when
// there is nothing to look for.
fun M3uEntry.lookedFor(): Pair<String, String>? {
    title?.let { return artist.orEmpty().trim() to it.trim() }
    val name = baseName(location).replace('_', ' ').replace(TrackNumber, "").trim()
    if (name.isEmpty()) return null
    val split = name.indexOf(" - ")
    return if (split > 0) name.substring(0, split).trim() to name.substring(split + 3).trim() else "" to name
}

// The song found online that an entry names, among what a search for it
// found: the same recording by the same artist, else the same song in any
// version. With no artist named, the first with the same title.
fun bestFind(songs: List<TrackEntity>, artist: String, title: String): String? = when {
    artist.isEmpty() -> songs.firstOrNull { SongIdentity.sameTitle(title, it.title).isSame }?.id
    else -> (
        songs.firstOrNull { sameRecording(it.title, it.artist, title, artist) }
            ?: songs.firstOrNull { sameSongAnyVersion(it.title, it.artist, title, artist) }
        )?.id
}

// The songs of a playlist file in the file's order: the library's matches,
// with the songs found online for the entries it missed in their places.
// An entry nobody found is left out.
fun withFinds(entries: List<M3uEntry>, match: M3uMatch, found: Map<M3uEntry, String>): List<String> {
    val missed = match.missed.toHashSet()
    val library = match.trackIds.iterator()
    return entries.mapNotNull { entry -> if (entry in missed) found[entry] else if (library.hasNext()) library.next() else null }
}
