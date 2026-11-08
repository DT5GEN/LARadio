package app.smradio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smradio.data.Genre
import app.smradio.playback.rec.RecPlayState
import app.smradio.playback.rec.Recording
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved songs. Filter by genre and by station (tags set when the song was recorded), tap to play.
 */
@Composable
fun RecordingsScreen(
    recs: List<Recording>,
    genres: List<Genre>,
    play: RecPlayState,
    landscape: Boolean,
    onBack: () -> Unit,
    onPlay: (Recording, List<Recording>) -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onDelete: (Recording) -> Unit,
) {
    var genreFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var stationFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<Recording?>(null) }

    val genreIds = recs.flatMap { it.genres }.toSet()
    val genreChips = genres.filter { it.id in genreIds }.sortedBy { it.order }
    val stationChips = recs.map { it.stationName }.filter { it.isNotEmpty() }.distinct().sorted()
    // a filter whose recordings were all deleted must not leave an empty screen
    val gf = genreFilter?.takeIf { it in genreIds }
    val sf = stationFilter?.takeIf { it in stationChips }
    val shown = recs.filter { (gf == null || gf in it.genres) && (sf == null || it.stationName == sf) }
    val genreName = genres.associate { it.id to it.nameRu }

    Column(Modifier.fillMaxSize().background(Bg)) {
        if (!landscape) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹ НАЗАД", color = Sand, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack).padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
            Text("Записи", color = TextMain, fontSize = if (landscape) 22.sp else 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.padding(start = 10.dp))
            Text("${shown.size}", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        }

        if (recs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Пока пусто.\nНажми ● REC во время песни — она сохранится сюда.",
                    color = Muted, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                )
            }
        } else {
            if (genreChips.isNotEmpty()) {
                ChipRow {
                    FilterChip("ВСЕ ЖАНРЫ", gf == null, Lit) { genreFilter = null }
                    for (g in genreChips) FilterChip(g.nameRu, gf == g.id, parseColor(g.color)) { genreFilter = if (gf == g.id) null else g.id }
                }
            }
            if (stationChips.isNotEmpty()) {
                ChipRow {
                    FilterChip("ВСЕ СТАНЦИИ", sf == null, Lit) { stationFilter = null }
                    for (s in stationChips) FilterChip(s, sf == s, Sand) { stationFilter = if (sf == s) null else s }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown, key = { it.id }) { r ->
                    RecordingRow(r, genreName, r.id == play.id, onClick = { onPlay(r, shown) }, onDelete = { toDelete = r })
                }
            }
        }

        if (play.id != null) {
            val cur = recs.find { it.id == play.id }
            Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(
                    cur?.let { (it.artist?.let { a -> "$a — " } ?: "") + it.title } ?: "",
                    color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(play.posMs / 1000), color = Lit, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    ProgressBar(
                        if (play.durMs > 0) play.posMs.toFloat() / play.durMs else 0f, onSeek,
                        Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Text(clock(play.durMs / 1000), color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    PlayerButton("⏮", onPrev)
                    PlayerButton(if (play.playing) "⏸" else "▶", onToggle, big = true)
                    PlayerButton("⏭", onNext)
                }
            }
        }
    }

    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = Panel,
            title = { Text("Удалить запись?", color = TextMain) },
            text = { Text((r.artist?.let { "$it — " } ?: "") + r.title, color = Muted) },
            confirmButton = {
                Text(
                    "УДАЛИТЬ", color = ErrorRed, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDelete(r); toDelete = null }.padding(12.dp),
                )
            },
            dismissButton = {
                Text("ОТМЕНА", color = Sand, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { toDelete = null }.padding(12.dp))
            },
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Text(
        text, color = if (selected) Bg else accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) accent else Color.Transparent)
            .border(BorderStroke(1.dp, accent.copy(alpha = if (selected) 1f else 0.5f)), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun RecordingRow(r: Recording, genreName: Map<String, String>, active: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val date = remember(r.timeMs) { SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(r.timeMs)) }
    val tags = listOf(
        r.stationName.takeIf { it.isNotEmpty() },
        r.genres.mapNotNull { genreName[it] }.take(2).joinToString(", ").takeIf { it.isNotEmpty() },
        date,
        clock(r.durationSec).let { if (r.partial) "~$it" else it },
    ).filterNotNull().joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (active) Dim else Panel2).clickable(onClick = onClick).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(r.title, color = if (active) Lit else TextMain, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.artist != null) Text(r.artist, color = Sand, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tags, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "✕", color = Muted, fontSize = 18.sp,
            modifier = Modifier.clickable(onClick = onDelete).padding(horizontal = 16.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    var width by remember { mutableStateOf(1) }
    Box(
        modifier
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { onSeek((it.x / width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Line)
                .onSizeChanged { width = it.width.coerceAtLeast(1) },
        ) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Lit))
        }
    }
}
