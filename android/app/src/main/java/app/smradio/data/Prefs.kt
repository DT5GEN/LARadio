package app.smradio.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

class Prefs(private val context: Context) {
    private val favoritesKey = stringSetPreferencesKey("favorites")

    val favorites: Flow<Set<String>> = context.settingsStore.data.map { it[favoritesKey] ?: emptySet() }

    suspend fun toggleFavorite(stationId: String) {
        context.settingsStore.edit { prefs ->
            val current = prefs[favoritesKey] ?: emptySet()
            prefs[favoritesKey] = if (stationId in current) current - stationId else current + stationId
        }
    }
}
