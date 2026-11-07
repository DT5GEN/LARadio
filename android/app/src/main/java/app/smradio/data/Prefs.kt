package app.smradio.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.smradio.playback.dsp.EQ_BANDS
import app.smradio.playback.dsp.EqState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

class Prefs(private val context: Context) {
    private val favoritesKey = stringSetPreferencesKey("favorites")

    val favorites: Flow<Set<String>> = context.settingsStore.data.map { it[favoritesKey] ?: emptySet() }

    private val eqEnabledKey = booleanPreferencesKey("eq_enabled")
    private val eqGainsKey = stringPreferencesKey("eq_gains")

    val eq: Flow<EqState> = context.settingsStore.data.map { prefs ->
        val gains = prefs[eqGainsKey]?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == EQ_BANDS.size } ?: List(EQ_BANDS.size) { 0f }
        EqState(prefs[eqEnabledKey] ?: true, gains)
    }

    suspend fun saveEq(state: EqState) {
        context.settingsStore.edit { prefs ->
            prefs[eqEnabledKey] = state.enabled
            prefs[eqGainsKey] = state.gains.joinToString(",")
        }
    }

    private val regionKey = stringPreferencesKey("region")
    val region: Flow<String> = context.settingsStore.data.map { it[regionKey] ?: "la" }
    suspend fun saveRegion(id: String) {
        context.settingsStore.edit { it[regionKey] = id }
    }

    private val qualityKey = stringPreferencesKey("quality")
    private val bufferKey = stringPreferencesKey("buffer")

    val settings: Flow<Settings> = context.settingsStore.data.map { prefs ->
        Settings(
            quality = runCatching { QualitySetting.valueOf(prefs[qualityKey] ?: "") }.getOrDefault(QualitySetting.AUTO),
            buffer = runCatching { BufferProfile.valueOf(prefs[bufferKey] ?: "") }.getOrDefault(BufferProfile.NORMAL),
        )
    }

    suspend fun saveSettings(settings: Settings) {
        context.settingsStore.edit { prefs ->
            prefs[qualityKey] = settings.quality.name
            prefs[bufferKey] = settings.buffer.name
        }
    }

    suspend fun toggleFavorite(stationId: String) {
        context.settingsStore.edit { prefs ->
            val current = prefs[favoritesKey] ?: emptySet()
            prefs[favoritesKey] = if (stationId in current) current - stationId else current + stationId
        }
    }
}
