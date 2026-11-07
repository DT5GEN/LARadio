package app.smradio.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which stream of a station to prefer (docs/CONCEPT.md 5.3). AUTO = decided by network and stalls. */
enum class QualitySetting { AUTO, HIGH, MEDIUM, LOW }

/**
 * Buffer / reconnect profiles.
 * Live radio arrives in real time, so the cushion is mostly built by waiting [startMs] before the
 * first sound; the big protection against dropouts is [retries] + the stall watchdog.
 */
enum class BufferProfile(
    val startMs: Int,
    val rebufferMs: Int,
    val maxMs: Int,
    val maxBytes: Int,
    val retries: Int,
    val stallRestartMs: Long,
) {
    FAST(1_000, 2_000, 30_000, 8 shl 20, 3, 8_000L),
    NORMAL(2_500, 5_000, 120_000, 16 shl 20, 6, 12_000L),
    ROAD(6_000, 8_000, 600_000, 64 shl 20, 12, 15_000L),
}

data class Settings(
    val quality: QualitySetting = QualitySetting.AUTO,
    val buffer: BufferProfile = BufferProfile.NORMAL,
)

/** In-process holder, shared by UI (writes) and PlaybackService (reads / observes). */
class SettingsController(initial: Settings) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Settings> = _state
    fun update(block: (Settings) -> Settings) { _state.value = block(_state.value) }
}

/** Sleep timer end time (epoch ms), null = off. The service owns the countdown. */
class SleepTimer {
    val endAtMs = MutableStateFlow<Long?>(null)
    fun start(minutes: Int) { endAtMs.value = System.currentTimeMillis() + minutes * 60_000L }
    fun cancel() { endAtMs.value = null }
}
