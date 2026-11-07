package app.smradio.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tanh

/**
 * 5-band equalizer on decoded 16-bit PCM (runs inside ExoPlayer's audio pipeline).
 * Loudness is kept: only a small level trim is applied (boost -> slightly down, cut -> slightly up) and a
 * peak limiter (instant attack, 150 ms release) catches the peaks that boosting creates, followed by a soft clipper.
 */
@OptIn(UnstableApi::class)
class EqAudioProcessor(private val controller: EqController) : BaseAudioProcessor() {
    private var channels = 0
    private var sampleRate = 0
    private var filters: Array<Biquad> = emptyArray()
    private var appliedGains: FloatArray? = null
    private var preamp = 1.0
    private var limiterGain = 1.0
    private var releaseCoef = 0.0
    private var frameBuf = DoubleArray(2)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        filters = Array(EQ_BANDS.size) { Biquad(channels) }
        appliedGains = null
        limiterGain = 1.0
        releaseCoef = 1.0 - kotlin.math.exp(-1.0 / (0.15 * sampleRate))
        frameBuf = DoubleArray(channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!controller.enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        refreshCoefficients()
        val frames = size / (2 * channels)
        for (f in 0 until frames) {
            var peak = 0.0
            for (ch in 0 until channels) {
                var x = inputBuffer.getShort() / 32768.0 * preamp
                for (filter in filters) x = filter.process(x, ch)
                frameBuf[ch] = x
                peak = max(peak, abs(x))
            }
            // peak limiter: instant attack, slow release
            val target = if (peak > LIMIT) LIMIT / peak else 1.0
            limiterGain = if (target < limiterGain) target else limiterGain + (target - limiterGain) * releaseCoef
            for (ch in 0 until channels) {
                val v = (softClip(frameBuf[ch] * limiterGain) * 32767.0).roundToInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
        }
        out.put(inputBuffer) // leftover bytes of an incomplete frame, normally none
        out.flip()
    }

    private fun refreshCoefficients() {
        val g = controller.gains
        if (g === appliedGains) return
        appliedGains = g
        for (i in filters.indices) filters[i].configure(EQ_BANDS[i], g.getOrElse(i) { 0f }.toDouble(), sampleRate)
        val maxBoost = max(0f, g.maxOrNull() ?: 0f)
        val maxCut = max(0f, -(g.minOrNull() ?: 0f))
        // small trim only; the limiter handles the rest, so the overall volume does not collapse
        preamp = 10.0.pow((-0.2 * maxBoost + 0.3 * maxCut) / 20.0)
    }

    private fun softClip(x: Double): Double {
        val a = abs(x)
        return if (a <= KNEE) x else sign(x) * (KNEE + (1.0 - KNEE) * tanh((a - KNEE) / (1.0 - KNEE)))
    }

    override fun onFlush() {
        filters.forEach { it.reset() }
        limiterGain = 1.0
    }

    override fun onReset() {
        filters = emptyArray()
        appliedGains = null
    }

    private companion object {
        const val KNEE = 0.97
        const val LIMIT = 0.95
    }
}
