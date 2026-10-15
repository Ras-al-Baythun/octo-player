package app.winters.octo.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.albumsChosenData by preferencesDataStore("albums_chosen")

private val CHOSEN = stringSetPreferencesKey("ids")

// The albums the listener played or downloaded as a whole album, by id, so
// they show as albums however few of their songs the library has (see
// isOwnAlbum).
@Singleton
class AlbumsChosen @Inject constructor(@ApplicationContext private val context: Context) {
    val ids: Flow<Set<String>> = context.albumsChosenData.data.map { it[CHOSEN].orEmpty() }

    suspend fun add(albumId: String) {
        context.albumsChosenData.edit { it[CHOSEN] = it[CHOSEN].orEmpty() + albumId }
    }
}
