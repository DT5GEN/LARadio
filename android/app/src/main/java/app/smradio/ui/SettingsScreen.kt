package app.smradio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smradio.data.BufferProfile
import app.smradio.data.QualitySetting
import app.smradio.data.Settings
import kotlinx.coroutines.delay

private val QUALITY_OPTIONS = listOf(
    "Авто" to QualitySetting.AUTO,
    "Высокое" to QualitySetting.HIGH,
    "Среднее" to QualitySetting.MEDIUM,
    "Экономия" to QualitySetting.LOW,
)

private val QUALITY_HINT = mapOf(
    QualitySetting.AUTO to "По Wi-Fi — лучший поток. В мобильной сети и после частых запинок само переходит на более лёгкий поток (если у станции он есть).",
    QualitySetting.HIGH to "Всегда самый качественный поток станции.",
    QualitySetting.MEDIUM to "Золотая середина: потоки примерно 64–96 кбит/с.",
    QualitySetting.LOW to "Минимум трафика (до 48 кбит/с). Для слабой связи.",
)

private val BUFFER_OPTIONS = listOf(
    "Быстрый" to BufferProfile.FAST,
    "Обычный" to BufferProfile.NORMAL,
    "Дорога" to BufferProfile.ROAD,
)

private val BUFFER_HINT = mapOf(
    BufferProfile.FAST to "Звук через ~1 с. Запас маленький — для хорошей связи.",
    BufferProfile.NORMAL to "Звук через ~3 с, 6 повторных подключений при обрыве.",
    BufferProfile.ROAD to "Звук через ~6 с, запас до 10 минут, 12 повторных подключений. Для дороги и слабой связи.",
)

private val SLEEP_OPTIONS = listOf("Выкл" to 0, "15" to 15, "30" to 30, "60" to 60, "90" to 90)

@Composable
fun SettingsScreen(
    settings: Settings,
    sleepEnd: Long?,
    onQuality: (QualitySetting) -> Unit,
    onBuffer: (BufferProfile) -> Unit,
    onSleep: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            "‹ Назад", color = Sand, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 10.dp, horizontal = 4.dp),
        )
        Text("Настройки", color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))

        Section("КАЧЕСТВО ПОТОКА")
        Chips(QUALITY_OPTIONS, settings.quality, onQuality)
        Hint(QUALITY_HINT[settings.quality] ?: "")
        Spacer(Modifier.height(22.dp))

        Section("БУФЕР И СВЯЗЬ")
        Chips(BUFFER_OPTIONS, settings.buffer, onBuffer)
        Hint(BUFFER_HINT[settings.buffer] ?: "")
        Hint(
            "Радио идёт в реальном времени, поэтому запас не накапливается быстрее воспроизведения. " +
                "Главная защита от обрывов — автоматическое переподключение: при потере сети плеер ждёт её и сам продолжает эфир.",
            dim = true,
        )
        Spacer(Modifier.height(22.dp))

        Section("ТАЙМЕР СНА, МИН")
        Chips(SLEEP_OPTIONS, if (sleepEnd == null) 0 else -1, onSleep)
        if (sleepEnd != null) SleepLeft(sleepEnd)
        Hint("В последние 15 секунд звук плавно затихает.", dim = true)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SleepLeft(endAtMs: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val left = ((endAtMs - now) / 1000).coerceAtLeast(0)
    Hint("Выключится через %d:%02d".format(left / 60, left % 60))
}

@Composable
private fun Section(text: String) {
    Text(text, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Hint(text: String, dim: Boolean = false) {
    Text(text, color = if (dim) Muted.copy(alpha = 0.7f) else Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun <T> Chips(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, value) in options) {
            val on = value == selected
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (on) Lit else Panel2)
                    .border(BorderStroke(1.dp, if (on) Lit else Line), shape)
                    .clickable { onSelect(value) }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Bg else TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}
