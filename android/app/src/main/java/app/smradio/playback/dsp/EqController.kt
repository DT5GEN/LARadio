package app.smradio.playback.dsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EqState(val enabled: Boolean = true, val gains: List<Float> = List(EQ_BANDS.size) { 0f })

/**
 * Shared equalizer settings. The UI thread writes, the audio thread reads
 * (plain volatile fields, the gains array is replaced as a whole on every change).
 */
class EqController {
    @Volatile var enabled: Boolean = true
        private set

    @Volatile var gains: FloatArray = FloatArray(EQ_BANDS.size)
        private set

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state

    fun update(enabled: Boolean, gains: List<Float>) {
        val fixed = List(EQ_BANDS.size) { gains.getOrElse(it) { 0f }.coerceIn(-12f, 12f) }
        this.gains = fixed.toFloatArray()
        this.enabled = enabled
        _state.value = EqState(enabled, fixed)
    }
}
