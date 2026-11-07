package app.smradio.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

// Mirrors docs/CONCEPT.md section 4.3 (schemaVersion 1). Unknown fields are ignored.
@Serializable
data class Catalog(
    val schemaVersion: Int = 1,
    val generatedAt: String = "",
    val regions: List<Region> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val stations: List<Station> = emptyList(),
)

@Serializable
data class Region(
    val id: String,
    val name: String = "",
    val nameRu: String = "",
    val flag: String = "",
)

@Serializable
data class Genre(
    val id: String,
    val name: String,
    val nameRu: String,
    val order: Int = 0,
    val color: String = "#67ff91",
)

@Serializable
data class Station(
    val id: String,
    val name: String,
    val region: String = "la",
    val callsign: String? = null,
    val frequency: String = "WEB",
    val city: String? = null,
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val logo: String? = null,
    val homepage: String? = null,
    val officialPlayer: String? = null,
    val streams: List<Stream> = emptyList(),
    val status: String = "ok",
) {
    val playable: Boolean get() = streams.isNotEmpty()
}

@Serializable
data class Stream(
    val url: String,
    val format: String = "mp3",
    val bitrate: Int? = null,
    val quality: String = "medium",
    val https: Boolean = true,
    val icy: Boolean = false,
)

/**
 * Catalog loading order: newest of (downloaded cache, copy bundled in assets).
 * [refresh] downloads the latest catalog published by GitHub Actions.
 */
class CatalogRepository(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cacheFile = File(context.filesDir, "stations-cache.json")

    private val _catalog = MutableStateFlow(loadInitial())
    val catalog: StateFlow<Catalog> = _catalog
    val current: Catalog get() = _catalog.value

    private fun parse(text: String): Catalog? = try {
        json.decodeFromString<Catalog>(text).takeIf { it.schemaVersion == 1 && it.stations.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "catalog parse failed: ${e.message}")
        null
    }

    private fun loadInitial(): Catalog {
        val bundled = context.assets.open("stations.json").bufferedReader().use { it.readText() }
            .let { parse(it) } ?: Catalog()
        val cached = if (cacheFile.exists()) parse(cacheFile.readText()) else null
        // ISO-8601 timestamps compare correctly as strings
        return if (cached != null && cached.generatedAt > bundled.generatedAt) cached else bundled
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(CATALOG_URL).header("User-Agent", "SantaMonicaRadio/0.1").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "catalog refresh: HTTP ${response.code}")
                    return@withContext
                }
                val text = response.body?.string() ?: return@withContext
                val parsed = parse(text) ?: return@withContext
                cacheFile.writeText(text)
                _catalog.value = parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "catalog refresh failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CatalogRepository"
        const val CATALOG_URL = "https://dt5gen.github.io/LARadio/stations.json"
    }
}
