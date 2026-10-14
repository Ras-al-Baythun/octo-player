package app.winters.octo.ui.preview

import app.winters.octo.health.countText

// The words both apps use when Choose version is taken on a library song:
// its file is swapped for a download of the video picked, so the question
// before it, and what is said after, read alike on the desktop and the phone.

// The question before the file goes.
const val REPLACE_VERSION_TITLE = "Replace the file with this version?"

// What replacing does, for the song and the video picked, by their titles.
// The server keeps the old file in its trash for `keepDays` (0 is until
// someone clears it).
fun replaceVersionBody(song: String, choice: String, keepDays: Int): String {
    val kept = if (keepDays > 0) "for ${countText(keepDays, "day", "days")}" else "until someone clears it"
    return "${songName(song)} is replaced by ${choiceName(choice)}. The file you have now goes to the trash $kept."
}

// The line once the server took the replacement on: the download runs and
// the file is swapped when it lands.
fun fetchingVersionLine(song: String, choice: String): String = "Fetching ${choiceName(choice)} for ${songName(song)}"

// The line when the server only rehearsed it.
const val DRY_RUN_NOTICE = "Dry run: nothing is changed"

// The line for a replacement the server did not take on, in its own words
// where it gave any; `state` is what it answered instead.
fun replaceRefusedLine(state: String, detail: String?): String =
    detail?.takeIf { it.isNotBlank() } ?: "Couldn't replace the file (${state.ifBlank { "no answer" }})."

private fun songName(title: String): String = title.ifBlank { "The song" }

private fun choiceName(title: String): String = title.ifBlank { "the chosen video" }
