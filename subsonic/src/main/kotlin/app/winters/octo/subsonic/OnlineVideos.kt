package app.winters.octo.subsonic

// The OpenSubsonic extension an Octo server lists when its search also
// finds YouTube videos no catalog lists (an ambience, a mix, an upload) and
// the video a pasted YouTube link points at. Such a song says so
// (Song.octoVideo), and a search can ask for songs, videos or both.
const val OCTO_VIDEOS = "octoVideos"

// What a search looks for online: songs from the catalogs, YouTube's
// videos, or songs and then the videos whose titles have the words.
enum class OnlineFind(val param: String, val label: String) {
    Songs("songs", "Music"),
    Videos("videos", "Videos"),
    Both("both", "Both"),
}
