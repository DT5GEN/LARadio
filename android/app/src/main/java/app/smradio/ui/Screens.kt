package app.smradio.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smradio.data.Catalog
import app.smradio.data.Genre
import app.smradio.data.Region
import app.smradio.data.Station
import app.smradio.playback.rec.RecState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** UI strings (Russian). Move to string resources when a second language is added (open question Q6). */
private object T {
    const val TAGLINE = "FM / ЛОС-АНДЖЕЛЕС / LIVE"
    const val FAVORITES = "ИЗБРАННОЕ"
    const val GENRES = "ЖАНРЫ"
    const val STATIONS_SUFFIX = "ст."
    const val PICK_STATION = "Выберите станцию"
    const val UNAVAILABLE = "Станция недоступна"
    const val OFFICIAL = "Официальный плеер"
    const val BACK = "‹ Назад"
    const val CONNECTING = "Подключение…"
    const val LIVE = "В эфире"
    const val NO_STREAM = "нет прямого потока"
    const val SETTINGS = "НАСТРОЙКИ"
    const val RECONNECTING = "Переподключение…"
    const val OFFLINE = "Нет сети — ждём…"
}

@Composable
fun AppRoot(vm: PlayerViewModel) {
    val catalog by vm.catalog.collectAsState()
    val favorites by vm.favorites.collectAsState(initial = emptySet())
    val savedRegion by vm.region.collectAsState(initial = "la")
    val ui by vm.ui.collectAsState()
    val eq by vm.eq.collectAsState()
    val rec by vm.rec.collectAsState()
    var genreId by rememberSaveable { mutableStateOf<String?>(null) }
    var showEq by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var carMode by rememberSaveable { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()
    val sleepEnd by vm.sleepEnd.collectAsState()
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // one tab per region (America / Spain / Sweden); everything below works on the selected region only
    val regions = catalog.regions
    val regionId = regions.find { it.id == savedRegion }?.id ?: regions.firstOrNull()?.id ?: "la"
    val regionStations = catalog.stations.filter { it.region == regionId }
    val regionCatalog = catalog.copy(
        stations = regionStations,
        genres = catalog.genres.filter { g -> regionStations.any { g.id in it.genres } },
    )
    val onRegion: (String) -> Unit = { id -> if (id != regionId) { genreId = null; vm.setRegion(id) } }

    BackHandler(enabled = genreId != null) { genreId = null }
    BackHandler(enabled = showEq) { showEq = false }
    BackHandler(enabled = showSettings) { showSettings = false }

    if (carMode) {
        CarScreen(
            catalog = regionCatalog, favorites = favorites, ui = ui,
            regions = regions, regionId = regionId, onRegion = onRegion,
            onPlay = { station, list -> vm.play(station, list) },
            onToggle = vm::togglePlay, onPrev = vm::previous, onNext = vm::next,
            onExit = { carMode = false },
            landscape = landscape,
            rec = rec, onRec = vm::toggleRecord,
        )
        return
    }

    fun openOfficial(stationId: String?) {
        val url = catalog.stations.find { it.id == stationId }?.officialPlayer ?: return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val body: @Composable () -> Unit = {
            val genre = regionCatalog.genres.find { it.id == genreId }
            if (showSettings) {
                SettingsScreen(
                    settings = settings,
                    sleepEnd = sleepEnd,
                    onQuality = vm::setQuality,
                    onBuffer = vm::setBuffer,
                    onSleep = vm::setSleepTimer,
                    onBack = { showSettings = false },
                )
            } else if (showEq) {
                EqScreen(state = eq, onChange = vm::setEq, onBack = { showEq = false })
            } else if (genre == null) {
                HomeScreen(
                    catalog = regionCatalog,
                    regions = regions,
                    regionId = regionId,
                    onRegion = onRegion,
                    favorites = favorites,
                    currentId = ui.stationId,
                    landscape = landscape,
                    onGenre = { genreId = it.id },
                    onSettings = { showSettings = true },
                    onCar = { carMode = true },
                    onPlay = { station, list -> vm.play(station, list) },
                    onFavorite = vm::toggleFavorite,
                    onOfficial = ::openOfficial,
                )
            } else {
                GenreScreen(
                    genre = genre,
                    landscape = landscape,
                    stations = regionStations.filter { genre.id in it.genres },
                    favorites = favorites,
                    currentId = ui.stationId,
                    onBack = { genreId = null },
                    onPlay = { station, list -> vm.play(station, list) },
                    onFavorite = vm::toggleFavorite,
                    onOfficial = ::openOfficial,
                )
            }
    }

    if (landscape) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density * 0.85f, d.fontScale)) {
            LandscapeShell(
                ui = ui,
                eqOn = eq.enabled,
                rec = rec,
                onRec = vm::toggleRecord,
                canBack = showSettings || showEq || genreId != null,
                onBack = {
                    when {
                        showSettings -> showSettings = false
                        showEq -> showEq = false
                        else -> genreId = null
                    }
                },
                onToggle = vm::togglePlay,
                onPrev = vm::previous,
                onNext = vm::next,
                onEq = { showEq = true },
                onSettings = { showSettings = true },
                onCar = { carMode = true },
                onOfficial = { openOfficial(ui.stationId) },
                content = body,
            )
        }
    } else {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                MiniPlayer(
                    ui = ui,
                    onToggle = vm::togglePlay,
                    onPrev = vm::previous,
                    onNext = vm::next,
                    onOfficial = { openOfficial(ui.stationId) },
                    eqOn = eq.enabled,
                    onEq = { showEq = true },
                    rec = rec,
                    onRec = vm::toggleRecord,
                    bufferedSec = vm.bufferedSec,
                    sleepEnd = vm.sleepEnd,
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { body() }
        }
    }
}

@Composable
private fun HomeScreen(
    catalog: Catalog,
    regions: List<Region>,
    regionId: String,
    onRegion: (String) -> Unit,
    favorites: Set<String>,
    currentId: String?,
    landscape: Boolean,
    onGenre: (Genre) -> Unit,
    onSettings: () -> Unit,
    onCar: () -> Unit,
    onPlay: (Station, List<Station>) -> Unit,
    onFavorite: (String) -> Unit,
    onOfficial: (String) -> Unit,
) {
    val favStations = catalog.stations.filter { it.id in favorites }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!landscape) item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(T.TAGLINE, color = Sand, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Santa Monica Radio", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                }
                for ((label, action) in listOf("МАШИНА" to onCar, T.SETTINGS to onSettings)) {
                    Text(
                        label, color = Sand, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(BorderStroke(1.dp, Line), RoundedCornerShape(8.dp))
                            .clickable(onClick = action)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (regions.size > 1) item { RegionTabs(regions, regionId, onRegion) }
        if (favStations.isNotEmpty()) {
            item { SectionTitle(T.FAVORITES) }
            items(favStations, key = { "fav-" + it.id }) { station ->
                StationRow(station, station.id == currentId, true, { onPlay(station, favStations) }, { onFavorite(station.id) }, { onOfficial(station.id) })
            }
        }
        item { SectionTitle(T.GENRES) }
        items(catalog.genres.sortedBy { it.order }.chunked(if (landscape) 3 else 2), key = { row -> row.first().id }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (genre in row) {
                    GenreTile(
                        genre = genre,
                        count = catalog.stations.count { genre.id in it.genres },
                        modifier = Modifier.weight(1f),
                        onClick = { onGenre(genre) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GenreScreen(
    genre: Genre,
    landscape: Boolean,
    stations: List<Station>,
    favorites: Set<String>,
    currentId: String?,
    onBack: () -> Unit,
    onPlay: (Station, List<Station>) -> Unit,
    onFavorite: (String) -> Unit,
    onOfficial: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (landscape) item {
            Text(genre.nameRu, color = parseColor(genre.color), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        } else item {
            Column(Modifier.statusBarsPadding().padding(top = 8.dp, bottom = 8.dp)) {
                Text(
                    T.BACK, color = Sand, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onBack).padding(vertical = 10.dp, horizontal = 4.dp),
                )
                Text(genre.nameRu, color = parseColor(genre.color), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(genre.name, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        items(stations, key = { it.id }) { station ->
            StationRow(station, station.id == currentId, station.id in favorites, { onPlay(station, stations) }, { onFavorite(station.id) }, { onOfficial(station.id) })
        }
    }
}

@Composable
fun RegionTabs(regions: List<Region>, selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in regions) {
            val on = r.id == selectedId
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (on) Lit else Panel2)
                    .border(BorderStroke(1.dp, if (on) Lit else Line), shape)
                    .clickable { onSelect(r.id) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("${r.flag} ${r.nameRu}", color = if (on) Bg else TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun GenreTile(genre: Genre, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val accent = parseColor(genre.color)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Panel2)
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .height(76.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).fillMaxSize().background(accent))
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text(genre.nameRu, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$count ${T.STATIONS_SUFFIX}", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onOfficial: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val borderColor = if (isCurrent) Lit.copy(alpha = 0.7f) else Line
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel2)
            .border(BorderStroke(1.dp, borderColor), shape)
            .clickable(onClick = if (station.playable) onClick else onOfficial)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            station.frequency.replace(" FM", "").replace(" AM", " AM"),
            color = Sand, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp,
            modifier = Modifier.width(78.dp), maxLines = 1,
        )
        Column(Modifier.weight(1f)) {
            Text(station.name, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (station.playable) (station.description ?: "") else T.NO_STREAM,
                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) Text("●", color = Lit, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
        Box(Modifier.size(48.dp).clickable(onClick = onFavorite), contentAlignment = Alignment.Center) {
            Text(if (isFavorite) "♥" else "♡", color = if (isFavorite) Hot else Muted, fontSize = 22.sp)
        }
    }
}

@Composable
private fun MiniPlayer(
    ui: PlayerUi,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOfficial: () -> Unit,
    eqOn: Boolean,
    onEq: () -> Unit,
    rec: RecState,
    onRec: () -> Unit,
    bufferedSec: StateFlow<Int>,
    sleepEnd: StateFlow<Long?>,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .border(BorderStroke(1.dp, Line))
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (ui.stationId == null) {
            Text(T.PICK_STATION, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            return@Column
        }
        InfoLine(ui.info, bufferedSec, sleepEnd, rec, onRec, eqOn, onEq)
        SegmentBars(
            active = ui.isPlaying,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ui.failed) ErrorRed else if (ui.isPlaying) Lit else Dim))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.stationName ?: "", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    ui.failed -> T.UNAVAILABLE
                    ui.offline -> T.OFFLINE
                    ui.reconnecting -> T.RECONNECTING
                    ui.buffering -> T.CONNECTING
                    ui.track != null -> ui.track
                    ui.isPlaying -> T.LIVE
                    else -> ""
                }
                Text(rec.message ?: sub, color = if (ui.failed) ErrorRed else Lit, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayerButton("⏮", onPrev)
            PlayerButton(if (ui.isPlaying || ui.buffering || ui.reconnecting || ui.offline) "⏸" else "▶", onToggle, big = true)
            PlayerButton("⏭", onNext)
        }
        if (ui.failed) {
            Text(
                T.OFFICIAL, color = Sand, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onOfficial).padding(top = 6.dp, bottom = 4.dp),
            )
        }
    }
}

/** Small LED-style line above the bars: stream info on the left, buffer / sleep timer on the right. */
@Composable
private fun InfoLine(
    info: String,
    bufferedSec: StateFlow<Int>,
    sleepEnd: StateFlow<Long?>,
    rec: RecState,
    onRec: () -> Unit,
    eqOn: Boolean,
    onEq: () -> Unit,
) {
    val buffered by bufferedSec.collectAsState()
    val end by sleepEnd.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) {
        if (end == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val parts = buildList {
        if (buffered > 0) add("БУФЕР ${buffered}с")
        end?.let {
            val left = ((it - now) / 1000).coerceAtLeast(0)
            add("СОН %d:%02d".format(left / 60, left % 60))
        }
    }
    val left = when {
        rec.recording -> "● ЗАПИСЬ ${clock(rec.seconds)}" + (rec.title?.let { "  $it" } ?: "")
        else -> info
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            left, color = if (rec.recording) ErrorRed else Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (parts.isNotEmpty() && !rec.recording) {
            Text(parts.joinToString("   "), color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
        }
        InfoChip(if (rec.recording) "■ СТОП" else "● REC", rec.recording, if (rec.recording || rec.canRecord) ErrorRed else Dim, onRec)
        InfoChip("EQ", eqOn, if (eqOn) Lit else Muted, onEq)
    }
}

@Composable
private fun InfoChip(text: String, filled: Boolean, color: Color, onClick: () -> Unit) {
    Text(
        text, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, color.copy(alpha = if (filled) 0.9f else 0.4f)), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

internal fun clock(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

@Composable
private fun PlayerButton(label: String, onClick: () -> Unit, big: Boolean = false) {
    Box(
        Modifier
            .size(if (big) 52.dp else 44.dp)
            .clip(CircleShape)
            .background(if (big) Lit else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (big) Bg else TextMain, fontSize = if (big) 22.sp else 20.sp)
    }
}


private fun statusText(ui: PlayerUi): String = when {
    ui.failed -> T.UNAVAILABLE
    ui.offline -> T.OFFLINE
    ui.reconnecting -> T.RECONNECTING
    ui.buffering -> T.CONNECTING
    ui.track != null -> ui.track
    ui.isPlaying -> T.LIVE
    else -> ""
}

/**
 * Landscape layout: list on the left (3/4), controls on the right (1/4, under the right thumb),
 * one thin "now playing" line at the very bottom. No visualizer, status bar hidden by MainActivity.
 */
@Composable
private fun LandscapeShell(
    ui: PlayerUi,
    eqOn: Boolean,
    rec: RecState,
    onRec: () -> Unit,
    canBack: Boolean,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onEq: () -> Unit,
    onSettings: () -> Unit,
    onCar: () -> Unit,
    onOfficial: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Bg).displayCutoutPadding().navigationBarsPadding()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.weight(3f).fillMaxHeight()) { content() }
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // fixed slots: nothing jumps when "back" appears; playback sits at the bottom, under the thumb
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.alpha(if (canBack) 1f else 0f)) {
                        SideButton("‹ НАЗАД", if (canBack) onBack else ({}))
                    }
                    SideButton(if (rec.recording) "■ ${clock(rec.seconds)}" else "● REC", onRec, active = rec.recording, danger = true)
                    SideButton("EQ", onEq, active = eqOn)
                    SideButton("МАШИНА", onCar)
                    SideButton(T.SETTINGS, onSettings)
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    PlayerButton("⏮", onPrev)
                    PlayerButton(if (ui.isPlaying || ui.buffering || ui.reconnecting || ui.offline) "⏸" else "▶", onToggle, big = true)
                    PlayerButton("⏭", onNext)
                }
            }
        }
        if (ui.stationId != null) {
            Row(
                Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (ui.failed) ErrorRed else if (ui.isPlaying) Lit else Dim))
                Spacer(Modifier.width(8.dp))
                Text(ui.stationName ?: "", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (rec.recording) "● ЗАПИСЬ ${clock(rec.seconds)}" + (rec.title?.let { "  $it" } ?: "") else rec.message ?: statusText(ui),
                    color = if (ui.failed || rec.recording) ErrorRed else Lit, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (ui.failed) {
                    Text(T.OFFICIAL, color = Sand, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onOfficial).padding(start = 8.dp))
                } else {
                    Text(ui.info, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SideButton(label: String, onClick: () -> Unit, active: Boolean = false, danger: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    val accent = if (danger) ErrorRed else Lit
    Box(
        Modifier.fillMaxWidth().clip(shape).background(Panel2).border(BorderStroke(1.dp, if (active) accent.copy(alpha = 0.8f) else Line), shape)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) accent else if (danger) ErrorRed.copy(alpha = 0.8f) else Sand, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}
