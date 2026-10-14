package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The OpenSubsonic extension an Octo server lists when a song found online
// can be played from a video of the listener's choosing: getPreviewChoices
// lists the videos the song could play from, setPreview picks one. The
// pick is also the video "Add to your library" fetches from YouTube.
const val OCTO_PREVIEW = "octoPreview"

// From this version getPreviewChoices and setPreview take a library song's
// id too, so a file can be replaced by another video's download (see
// LIBRARY_ACTION_REPLACE).
const val OCTO_PREVIEW_LIBRARY_VERSION = 2

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
// Music's own audio uploads first. For a library song (`library`, from
// OCTO_PREVIEW_LIBRARY_VERSION) `current` marks the video the file was
// downloaded from, when known.
@Serializable
data class PreviewChoices(
    val id: String,
    val choice: List<PreviewChoice> = emptyList(),
    val library: Boolean = false,
)
