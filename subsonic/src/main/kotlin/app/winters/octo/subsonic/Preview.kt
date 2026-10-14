package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The OpenSubsonic extension an Octo server lists when a song found online
// can be played from a video of the listener's choosing: getPreviewChoices
// lists the videos the song could play from, setPreview picks one. The
// pick is also the video "Add to your library" fetches from YouTube.
const val OCTO_PREVIEW = "octoPreview"

// Where a choice was found.
object PreviewSource {
    const val YOUTUBE_MUSIC = "YouTube Music"
    const val YOUTUBE = "YouTube"
}

// One video a song found online could play from.
@Serializable
data class PreviewChoice(
    val videoId: String,
    val title: String = "",
    // The channel it is on, or the artists YouTube Music names.
    val channel: String? = null,
    val lengthSeconds: Int? = null,
    val source: String = PreviewSource.YOUTUBE,
    // The video the song plays from now.
    val current: Boolean = false,
)

// The videos a song could play from, in the server's order: YouTube
// Music's own audio uploads first.
@Serializable
data class PreviewChoices(val id: String, val choice: List<PreviewChoice> = emptyList())
