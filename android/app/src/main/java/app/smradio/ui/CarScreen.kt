package app.smradio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smradio.data.Catalog
import app.smradio.data.Station

/**
 * Car mode: black screen, huge targets, NO animation (cheap to mirror over CarLife / Miracast),
 * screen stays on. docs/CONCEPT.md 5.6 / 5.6a.
 */
@Composable
fun CarScreen(
    catalog: Catalog,
    favorites: Set<String>,
    ui: PlayerUi,
    onPlay: (Station, List<Station>) -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var genreId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = genreId != null) { genreId = null }
    BackHandler(enabled = genreId == null) { onExit() }

    val genre = catalog.genres.find { it.id == genreId }
    val favStations = catalog.stations.filter { it.id in favorites && it.playable }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CarTextButton("‹ ВЫХОД", onExit)
            if (genre != null) CarTextButton("‹ ЖАНРЫ") { genreId = null }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (genre == null) {
                if (favStations.isNotEmpty()) {
                    item { CarSection("ИЗБРАННОЕ") }
                    items(favStations, key = { "f-" + it.id }) { st ->
                        CarRow(st.frequency.replace(" FM", ""), st.name, Lit, st.id == ui.stationId) { onPlay(st, favStations) }
                    }
                    item { CarSection("ЖАНРЫ") }
                }
                items(catalog.genres.sortedBy { it.order }, key = { it.id }) { g ->
                    val count = catalog.stations.count { g.id in it.genres }
                    CarRow("", g.nameRu, parseColor(g.color), false, sub = "$count ст.") { genreId = g.id }
                }
            } else {
                val list = catalog.stations.filter { genre.id in it.genres && it.playable }
                items(list, key = { it.id }) { st ->
                    CarRow(st.frequency.replace(" FM", ""), st.name, parseColor(genre.color), st.id == ui.stationId) { onPlay(st, list) }
                }
            }
        }
        // control panel
        Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (ui.stationId != null) {
                Text(ui.stationName ?: "", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    ui.failed -> "Станция недоступна"
                    ui.offline -> "Нет сети — ждём…"
                    ui.reconnecting -> "Переподключение…"
                    ui.buffering -> "Подключение…"
                    ui.track != null -> ui.track
                    ui.isPlaying -> "В эфире"
                    else -> "Пауза"
                }
                Text(sub, color = if (ui.failed) ErrorRed else Lit, fontFamily = FontFamily.Monospace, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text("Выберите станцию", color = Muted, fontSize = 18.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CarButton("⏮", 80, onPrev)
                CarButton(if (ui.isPlaying || ui.buffering || ui.reconnecting || ui.offline) "⏸" else "▶", 96, onToggle, filled = true)
                CarButton("⏭", 80, onNext)
            }
        }
    }
}

@Composable
private fun CarTextButton(text: String, onClick: () -> Unit) {
    Text(
        text, color = Sand, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 16.dp),
    )
}

@Composable
private fun CarSection(text: String) {
    Text(text, color = Muted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CarRow(freq: String, title: String, accent: Color, active: Boolean, sub: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().height(72.dp).clip(shape)
            .background(if (active) Dim else Panel2)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(8.dp).fillMaxSize().background(accent))
        if (freq.isNotEmpty()) {
            Text(freq, color = Sand, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.width(88.dp).padding(start = 12.dp), maxLines = 1)
        } else {
            Box(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CarButton(label: String, sizeDp: Int, onClick: () -> Unit, filled: Boolean = false) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(if (filled) Lit else Panel2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) Bg else TextMain, fontSize = (sizeDp / 2.4).sp)
    }
}
