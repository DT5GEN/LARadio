package app.smradio.playback

import app.smradio.data.Station
import app.smradio.data.Stream
import kotlin.math.abs

enum class QualityPref(val rank: Int) { HIGH(0), MEDIUM(1), LOW(2) }

object StreamSelector {
    private fun rank(quality: String) = when (quality) {
        "high" -> 0
        "medium" -> 1
        else -> 2
    }

    /**
     * Streams ordered by closeness to the wanted quality; ties keep catalog order
     * (the catalog already puts HTTPS first). The first element is tried first,
     * the rest are fallbacks.
     */
    fun order(station: Station, pref: QualityPref): List<Stream> =
        station.streams.withIndex()
            .sortedWith(compareBy({ abs(rank(it.value.quality) - pref.rank) }, { it.index }))
            .map { it.value }
}
