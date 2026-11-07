package app.smradio.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Spectrum levels for the retro bars. The audio thread publishes, the UI thread reads. */
object SpectrumBus {
    const val BARS = 16

    /** BARS values in 0..1, low to high frequency. */
    @Volatile var latest: FloatArray = FloatArray(BARS)

    /** A visualizer is on screen. */
    @Volatile var uiVisible = false

    /** The app is in the foreground (saves battery: no analysis with the screen off). */
    @Volatile var appForeground = false

    val wanted: Boolean get() = uiVisible && appForeground
}

/**
 * Passes audio through unchanged and, only when the UI wants it, computes a 16-band spectrum
 * (1024-point FFT, Hann window, logarithmic bands 40 Hz - 16 kHz).
 * It sits after the equalizer, so the bars show what you actually hear.
 */
@OptIn(UnstableApi::class)
class FftTapProcessor : BaseAudioProcessor() {
    private val n = 1024
    private val window = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
    private val ring = DoubleArray(n)
    private var ringPos = 0
    private var sinceLast = 0
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private var channels = 0
    private var sampleRate = 44100
    private var edgesHz = DoubleArray(SpectrumBus.BARS + 1)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        val top = minOf(16000.0, sampleRate * 0.45)
        val ratio = (top / 40.0).pow(1.0 / SpectrumBus.BARS)
        edgesHz = DoubleArray(SpectrumBus.BARS + 1) { 40.0 * ratio.pow(it) }
        ring.fill(0.0)
        ringPos = 0
        sinceLast = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (SpectrumBus.wanted) {
            val start = inputBuffer.position()
            val frames = size / (2 * channels)
            for (f in 0 until frames) {
                var sum = 0.0
                for (ch in 0 until channels) sum += inputBuffer.getShort(start + (f * channels + ch) * 2)
                ring[ringPos] = sum / channels / 32768.0
                ringPos = (ringPos + 1) % n
                if (++sinceLast >= HOP) {
                    sinceLast = 0
                    analyze()
                }
            }
        }
        out.put(inputBuffer)
        out.flip()
    }

    private fun analyze() {
        for (i in 0 until n) {
            re[i] = ring[(ringPos + i) % n] * window[i] // oldest sample first
            im[i] = 0.0
        }
        fft(re, im)
        val levels = FloatArray(SpectrumBus.BARS)
        val norm = n / 4.0
        for (b in 0 until SpectrumBus.BARS) {
            val lo = max(1, (edgesHz[b] * n / sampleRate).toInt())
            val hi = max(lo, (edgesHz[b + 1] * n / sampleRate).toInt() - 1)
            var peak = 0.0
            for (k in lo..hi) peak = max(peak, sqrt(re[k] * re[k] + im[k] * im[k]) / norm)
            val centerHz = sqrt(edgesHz[b] * edgesHz[b + 1])
            // +1.8 dB per octave above 40 Hz compensates the natural roll-off of music towards high frequencies
            val db = 20.0 * log10(peak + 1e-9) + 1.8 * log2(centerHz / 40.0)
            levels[b] = ((db + 66.0) / 60.0).toFloat().coerceIn(0f, 1f)
        }
        SpectrumBus.latest = levels
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val size = re.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < size) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun onFlush() {
        ring.fill(0.0)
        ringPos = 0
        sinceLast = 0
    }

    private companion object {
        const val HOP = 1024
    }
}
