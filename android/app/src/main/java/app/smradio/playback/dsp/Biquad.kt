package app.smradio.playback.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class BandType { LOW_SHELF, PEAKING, HIGH_SHELF }

data class BandSpec(val hz: Double, val type: BandType, val q: Double)

/** Same bands as design/tokens.json (eq.bands) and the future web version. */
val EQ_BANDS = listOf(
    BandSpec(60.0, BandType.LOW_SHELF, 0.707),
    BandSpec(230.0, BandType.PEAKING, 1.0),
    BandSpec(910.0, BandType.PEAKING, 1.0),
    BandSpec(3600.0, BandType.PEAKING, 1.0),
    BandSpec(14000.0, BandType.HIGH_SHELF, 0.707),
)

/** Second-order IIR filter (transposed direct form II), coefficients from the RBJ Audio EQ Cookbook. */
class Biquad(channels: Int) {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    private fun identity() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
    }

    fun configure(spec: BandSpec, gainDb: Double, sampleRate: Int) {
        if (abs(gainDb) < 0.01 || spec.hz >= sampleRate * 0.45) {
            identity()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * spec.hz / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * spec.q)
        val sqrtA2Alpha = 2.0 * sqrt(a) * alpha

        val nb0: Double
        val nb1: Double
        val nb2: Double
        val na0: Double
        val na1: Double
        val na2: Double
        when (spec.type) {
            BandType.PEAKING -> {
                nb0 = 1.0 + alpha * a
                nb1 = -2.0 * cosW
                nb2 = 1.0 - alpha * a
                na0 = 1.0 + alpha / a
                na1 = -2.0 * cosW
                na2 = 1.0 - alpha / a
            }
            BandType.LOW_SHELF -> {
                nb0 = a * ((a + 1) - (a - 1) * cosW + sqrtA2Alpha)
                nb1 = 2.0 * a * ((a - 1) - (a + 1) * cosW)
                nb2 = a * ((a + 1) - (a - 1) * cosW - sqrtA2Alpha)
                na0 = (a + 1) + (a - 1) * cosW + sqrtA2Alpha
                na1 = -2.0 * ((a - 1) + (a + 1) * cosW)
                na2 = (a + 1) + (a - 1) * cosW - sqrtA2Alpha
            }
            BandType.HIGH_SHELF -> {
                nb0 = a * ((a + 1) + (a - 1) * cosW + sqrtA2Alpha)
                nb1 = -2.0 * a * ((a - 1) + (a + 1) * cosW)
                nb2 = a * ((a + 1) + (a - 1) * cosW - sqrtA2Alpha)
                na0 = (a + 1) - (a - 1) * cosW + sqrtA2Alpha
                na1 = 2.0 * ((a - 1) - (a + 1) * cosW)
                na2 = (a + 1) - (a - 1) * cosW - sqrtA2Alpha
            }
        }
        b0 = nb0 / na0
        b1 = nb1 / na0
        b2 = nb2 / na0
        a1 = na1 / na0
        a2 = na2 / na0
    }

    fun process(x: Double, channel: Int): Double {
        val y = b0 * x + z1[channel]
        z1[channel] = b1 * x - a1 * y + z2[channel]
        z2[channel] = b2 * x - a2 * y
        return y
    }
}
