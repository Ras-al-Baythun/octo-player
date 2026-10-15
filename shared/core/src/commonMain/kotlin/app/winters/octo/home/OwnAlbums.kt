package app.winters.octo.home

// A song downloaded or played on its own still carries its album's tag,
// so the library lists an album for it. Such an album shows as one (on
// Home, in the library's albums) only when the listener has most of it,
// at least OWN_ALBUM_SONGS songs, or played or downloaded it as an album.
// Otherwise its songs show as songs.
const val OWN_ALBUM_SONGS = 3

fun isOwnAlbum(songCount: Int, chosenAsAlbum: Boolean): Boolean = chosenAsAlbum || songCount >= OWN_ALBUM_SONGS

// What Recently played shows: an album for a song from an album of the
// listener's own, else the song itself, newest first, each once.
sealed interface RecentPlay<out A, out S> {
    data class Album<A>(val album: A) : RecentPlay<A, Nothing>
    data class Song<S>(val song: S) : RecentPlay<Nothing, S>
}

// `plays` newest first, as (song, its album or null). `own` says whether
// an album shows as one.
fun <A, S> recentPlays(
    plays: List<Pair<S, A?>>,
    own: (A) -> Boolean,
    keyOfAlbum: (A) -> String,
    keyOfSong: (S) -> String,
    limit: Int,
): List<RecentPlay<A, S>> {
    val seen = HashSet<String>()
    val out = ArrayList<RecentPlay<A, S>>()
    for ((song, album) in plays) {
        if (out.size >= limit) break
        val item: RecentPlay<A, S> = if (album != null && own(album)) RecentPlay.Album(album) else RecentPlay.Song(song)
        val key = when (item) {
            is RecentPlay.Album -> "a:" + keyOfAlbum(item.album)
            is RecentPlay.Song -> "s:" + keyOfSong(item.song)
        }
        if (seen.add(key)) out += item
    }
    return out
}
