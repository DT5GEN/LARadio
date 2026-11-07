package app.smradio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smradio.playback.dsp.EqState
import kotlin.math.roundToInt

private const val RANGE_DB = 12f
private val BAND_LABELS = listOf("60", "230", "910", "3.6k", "14k")

// Same presets as design/tokens.json
private val PRESETS = listOf(
    "Ровно" to listOf(0f, 0f, 0f, 0f, 0f),
    "Меньше баса" to listOf(-6f, -3f, 0f, 0f, 0f),
    "Больше баса" to listOf(6f, 3f, 0f, 0f, 0f),
    "Авто" to listOf(-3f, 0f, 1f, 3f, 4f),
    "Голос" to listOf(-2f, -1f, 3f, 3f, 1f),
    "Высокие" to listOf(0f, 0f, 0f, 3f, 6f),
)

@Composable
fun EqScreen(state: EqState, onChange: (enabled: Boolean, gains: List<Float>) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ Назад", color = Sand, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onBack).padding(vertical = 10.dp, horizontal = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            val shape = RoundedCornerShape(10.dp)
            Text(
                if (state.enabled) "● ВКЛ" else "○ ВЫКЛ",
                color = if (state.enabled) Lit else Muted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(shape)
                    .border(BorderStroke(1.dp, if (state.enabled) Lit else Line), shape)
                    .clickable { onChange(!state.enabled, state.gains) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Text("ЭКВАЛАЙЗЕР", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
        Text("5 полос · ±12 дБ · двойное касание = 0", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (i in BAND_LABELS.indices) {
                Fader(
                    value = state.gains[i],
                    label = BAND_LABELS[i],
                    enabled = state.enabled,
                    onValue = { v -> onChange(state.enabled, state.gains.toMutableList().also { it[i] = v }) },
                )
            }
        }

        Text("ПРЕСЕТЫ", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, gains) in PRESETS) {
                val active = state.gains == gains
                val shape = RoundedCornerShape(10.dp)
                Text(
                    name, color = if (active) Bg else TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier
                        .clip(shape)
                        .background(if (active) Lit else Panel2)
                        .border(BorderStroke(1.dp, if (active) Lit else Line), shape)
                        .clickable { onChange(true, gains) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Fader(value: Float, label: String, enabled: Boolean, onValue: (Float) -> Unit) {
    val currentOnValue by rememberUpdatedState(onValue)
    val accent = if (enabled) Lit else Muted
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "%+.1f".format(value), color = accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .width(56.dp)
                .height(240.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { currentOnValue(0f) },
                        onTap = { currentOnValue(yToGain(it.y, size.height.toFloat())) },
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        currentOnValue(yToGain(change.position.y, size.height.toFloat()))
                        change.consume()
                    }
                }
        ) {
            val knobH = 20.dp.toPx()
            val trackTop = knobH / 2
            val trackH = size.height - knobH
            val cx = size.width / 2
            fun yOf(db: Float) = trackTop + (RANGE_DB - db) / (2 * RANGE_DB) * trackH
            // scale marks every 3 dB
            var db = -RANGE_DB
            while (db <= RANGE_DB + 0.01f) {
                val major = db == 0f
                val half = if (major) 16.dp.toPx() else 8.dp.toPx()
                drawLine(if (major) Muted else Line, Offset(cx - half, yOf(db)), Offset(cx + half, yOf(db)), strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx())
                db += 3f
            }
            // track + filled part between 0 dB and the knob
            drawRoundRect(Line, Offset(cx - 2.dp.toPx(), trackTop), Size(4.dp.toPx(), trackH), CornerRadius(2.dp.toPx()))
            val y0 = yOf(0f)
            val yv = yOf(value)
            drawRoundRect(accent.copy(alpha = 0.55f), Offset(cx - 2.dp.toPx(), minOf(y0, yv)), Size(4.dp.toPx(), kotlin.math.abs(yv - y0)), CornerRadius(2.dp.toPx()))
            // knob
            drawRoundRect(Panel2, Offset(cx - 22.dp.toPx(), yv - knobH / 2), Size(44.dp.toPx(), knobH), CornerRadius(4.dp.toPx()))
            drawRoundRect(accent, Offset(cx - 22.dp.toPx(), yv - knobH / 2), Size(44.dp.toPx(), knobH), CornerRadius(4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawLine(accent, Offset(cx - 14.dp.toPx(), yv), Offset(cx + 14.dp.toPx(), yv), strokeWidth = 2.dp.toPx())
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextMain, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("Гц", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

/** Touch position -> gain in dB (snapped to 0.5 dB). The knob travels between its half-height margins. */
private fun yToGain(y: Float, height: Float): Float {
    val knobMargin = height * (10f / 240f)
    val fraction = ((y - knobMargin) / (height - 2 * knobMargin)).coerceIn(0f, 1f)
    val db = RANGE_DB - fraction * 2 * RANGE_DB
    return (db * 2).roundToInt() / 2f
}
