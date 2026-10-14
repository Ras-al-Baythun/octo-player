package app.winters.octo.desktop.playlists

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.discovery.sameRecording
import app.winters.octo.discovery.sameSongAnyVersion
import app.winters.octo.playlists.ImportReport
import app.winters.octo.playlists.M3uEntry
import app.winters.octo.playlists.M3uLine
import app.winters.octo.playlists.M3uMatch
import app.winters.octo.playlists.M3uSong
import app.winters.octo.playlists.baseName
import app.winters.octo.playlists.fallbackLocation
import app.winters.octo.playlists.matchM3u
import app.winters.octo.playlists.parseM3u
import app.winters.octo.playlists.playlistNameOf
import app.winters.octo.playlists.shown
import app.winters.octo.subsonic.Song
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// Playlist files on the desktop: what a playlist writes out, and what a
// file brings in. Reading and matching are the phone's, from shared/core.

// The lines of a playlist file for these songs: each one's length, artist
// and title, and where the server keeps it, or "Artist - Title" when the
// server does not say.
fun m3uLinesOf(songs: List<Song>, paths: Map<String, String>): List<M3uLine> = songs.map { song ->
    val artist = song.artist.orEmpty()
    M3uLine(song.duration, artist, song.title, paths[song.id]?.takeIf(String::isNotBlank) ?: fallbackLocation(artist, song.title))
}

// What a playlist file comes to in this library: the report the notice
// line gives and the songs found, in the file's order. The file's entries
// and the match are kept, so the lines the library missed can be looked
// for online on a server that keeps such songs in its playlists.
class PlaylistImport(
    val report: ImportReport,
    val songs: List<Song>,
    val entries: List<M3uEntry> = emptyList(),
    val match: M3uMatch? = null,
)

// Finds a playlist file's songs in the library by their #EXTINF artist and
// title, or their file names. The desktop knows no paths for its songs, so
// it never matches by folder.
fun importPlaylist(text: String, fileName: String, library: List<Song>): PlaylistImport {
    val entries = parseM3u(text)
    val name = playlistNameOf(fileName)
    if (entries.isEmpty()) return PlaylistImport(ImportReport(name, 0, 0, emptyList()), emptyList())
    val byId = library.associateBy { it.id }
    val match = matchM3u(entries, library.map { M3uSong(it.id, it.title, it.artist.orEmpty(), it.duration * 1000L) }, emptyMap())
    val songs = match.trackIds.mapNotNull(byId::get)
    return PlaylistImport(ImportReport(name, songs.size, entries.size, match.missed.map { it.shown() }), songs, entries, match)
}

// Leading track numbers on a file name, like "01 ", "01 - " or "1. ".
private val TrackNumber = Regex("""^\d{1,3}\s*[-.)_]?\s+""")

// What an entry says of its song, for looking it up online: the #EXTINF
// artist and title, else the file's name read as "Artist - Title" or as a
// title alone. The artist is empty when nothing names one; null when there
// is nothing to look for.
fun M3uEntry.lookedFor(): Pair<String, String>? {
    title?.let { return artist.orEmpty().trim() to it.trim() }
    val name = baseName(location).replace('_', ' ').replace(TrackNumber, "").trim()
    if (name.isEmpty()) return null
    val split = name.indexOf(" - ")
    return if (split > 0) name.substring(0, split).trim() to name.substring(split + 3).trim() else "" to name
}

// The song found online that an entry names, among what a search for it
// found: the same recording by the same artist, else the same song in any
// version. With no artist named, the first whose title is the same song's.
fun bestFind(songs: List<Song>, artist: String, title: String): Song? = when {
    artist.isEmpty() -> songs.firstOrNull { SongIdentity.sameTitle(title, it.title).isSame }
    else -> songs.firstOrNull { sameRecording(it.title, it.artist.orEmpty(), title, artist) }
        ?: songs.firstOrNull { sameSongAnyVersion(it.title, it.artist.orEmpty(), title, artist) }
}

// The songs of a playlist file in the file's order: the library's matches,
// with the songs found online for the entries it missed in their places.
// An entry nobody found is left out.
fun withFinds(entries: List<M3uEntry>, match: M3uMatch, library: List<Song>, found: Map<M3uEntry, Song>): List<Song> {
    val missed = match.missed.toHashSet()
    val matched = library.iterator()
    return entries.mapNotNull { entry -> if (entry in missed) found[entry] else if (matched.hasNext()) matched.next() else null }
}

// A report with the lines found online taken out of the missed ones.
fun ImportReport.withFound(found: Map<M3uEntry, Song>, match: M3uMatch): ImportReport =
    copy(matched = matched + found.size, missed = match.missed.filterNot(found::containsKey).map { it.shown() }, found = found.size)

// The lines a file named that the library does not have, for the notice
// line's details: the first few, and how many more.
fun missedDetail(missed: List<String>, shown: Int = 3): String? {
    if (missed.isEmpty()) return null
    val more = missed.size - shown
    return "Not found: " + missed.take(shown).joinToString(", ") + if (more > 0) " and $more more" else ""
}

// The system's own file window, for a playlist file to open or save. On
// Windows the open window lists only playlist files; elsewhere the name
// filter does it. A file saved without an ending gets ".m3u".
fun choosePlaylistFile(save: Boolean, windows: Boolean, suggested: String? = null): File? {
    val dialog = FileDialog(null as Frame?, if (save) "Export playlist" else "Import a playlist file", if (save) FileDialog.SAVE else FileDialog.LOAD)
    when {
        suggested != null -> dialog.file = suggested
        !save && windows -> dialog.file = "*.m3u;*.m3u8"
    }
    if (!save) dialog.setFilenameFilter { _, name -> name.endsWith(".m3u", ignoreCase = true) || name.endsWith(".m3u8", ignoreCase = true) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val file = File(dialog.directory, name)
    return if (save && file.extension.isEmpty()) File(file.path + ".m3u") else file
}
