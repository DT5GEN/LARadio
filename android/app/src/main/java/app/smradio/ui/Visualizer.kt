package app.smradio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.smradio.playback.dsp.SpectrumBus
import androidx.compose.runtime.withFrameNanos
import kotlin.math.ceil
import kotlin.math.max

private val PeakColor = Color(0xFFEAFFEF)

/**
 * 80s tape-deck style level meter: segmented bars with falling peak caps.
 * Behaviour follows docs/CONCEPT.md 8.3 (fast attack, slow release, peak hold 0.6 s, top 2 segments "hot").
 * The animation loop stops by itself when nothing is playing and all bars have fallen to zero.
 */
@Composable
fun SegmentBars(
    active: Boolean,
    modifier: Modifier = Modifier,
    bars: Int = SpectrumBus.BARS,
    segments: Int = 12,
) {
    val level = remember { FloatArray(bars) }
    val peak = remember { FloatArray(bars) }
    val holdLeft = remember { FloatArray(bars) }
    var tick by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        SpectrumBus.uiVisible = true
        onDispose { SpectrumBus.uiVisible = false }
    }

    LaunchedEffect(active) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                val input = if (active) SpectrumBus.latest else null
                for (i in 0 until bars) {
                    val target = input?.getOrNull(i) ?: 0f
                    level[i] =
                        if (target > level[i]) level[i] + (target - level[i]) * 0.6f
                        else max(target, level[i] - 1.5f * dt)
                    if (level[i] >= peak[i]) {
                        peak[i] = level[i]
                        holdLeft[i] = 0.6f
                    } else if (holdLeft[i] > 0f) {
                        holdLeft[i] -= dt
                    } else {
                        peak[i] = max(level[i], peak[i] - 0.85f * dt)
                    }
                }
                tick = now
            }
            if (!active && level.all { it <= 0.001f } && peak.all { it <= 0.001f }) break
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val redraw = tick // reading the state schedules a redraw every frame
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * (bars - 1)) / bars
        val segH = (size.height - gap * (segments - 1)) / segments
        val corner = CornerRadius(2.dp.toPx())
        for (b in 0 until bars) {
            val x = b * (barW + gap)
            val lit = ceil(level[b] * segments).toInt().coerceIn(0, segments)
            val peakSeg = ceil(peak[b] * segments).toInt().coerceIn(0, segments)
            for (s in 0 until segments) {
                val y = size.height - (s + 1) * segH - s * gap
                val color = when {
                    s < lit -> if (s >= segments - 2) Hot else Lit
                    peakSeg > lit && s == peakSeg - 1 -> PeakColor
                    else -> Dim.copy(alpha = 0.55f)
                }
                drawRoundRect(color, Offset(x, y), Size(barW, segH), corner)
            }
        }
    }
}
