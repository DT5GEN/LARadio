package app.smradio.ui

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.smradio.App
import app.smradio.data.BufferProfile
import app.smradio.data.QualitySetting
import app.smradio.data.Settings
import app.smradio.data.Station
import app.smradio.playback.LiveStats
import app.smradio.playback.PlaybackService
import app.smradio.playback.dsp.EqState
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerUi(
    val stationId: String? = null,
    val stationName: String? = null,
    val track: String? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val failed: Boolean = false,
    val reconnecting: Boolean = false,
    val offline: Boolean = false,
    /** e.g. "128K · AAC · ВЫС" */
    val info: String = "",
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as App).container

    val catalog = container.catalogRepo.catalog
    val favorites = container.prefs.favorites
    val region = container.prefs.region
    val rec = container.recorder.state
    val eq: StateFlow<EqState> = container.eq.state
    val settings: StateFlow<Settings> = container.settings.state
    val sleepEnd: StateFlow<Long?> = container.sleep.endAtMs
    val bufferedSec: StateFlow<Int> = LiveStats.bufferedSec

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var errorStationId: String? = null
    private var status = PlaybackService.STATUS_OK
    private var kbps = 0
    private var format = ""
    private var qualityTag = ""

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
    }

    private val sessionListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            errorStationId = extras.getString(PlaybackService.EXTRA_ERROR_STATION)
            status = extras.getString(PlaybackService.EXTRA_STATUS) ?: PlaybackService.STATUS_OK
            kbps = extras.getInt(PlaybackService.EXTRA_KBPS, 0)
            format = extras.getString(PlaybackService.EXTRA_FORMAT) ?: ""
            qualityTag = extras.getString(PlaybackService.EXTRA_QUALITY) ?: ""
            sync()
        }
    }

    init {
        viewModelScope.launch { container.catalogRepo.refresh() }
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).setListener(sessionListener).buildAsync()
        controllerFuture = future
        future.addListener({
            controller = future.get().also { it.addListener(playerListener) }
            sync()
        }, ContextCompat.getMainExecutor(app))
    }

    private fun sync() {
        val c = controller ?: return
        val md = c.mediaMetadata
        val station = md.artist?.toString() ?: md.title?.toString()
        val title = md.title?.toString()
        val id = c.currentMediaItem?.mediaId
        _ui.value = PlayerUi(
            stationId = id,
            stationName = station,
            track = title?.takeIf { it != station },
            isPlaying = c.isPlaying,
            buffering = c.playWhenReady && c.playbackState == Player.STATE_BUFFERING,
            failed = id != null && id == errorStationId,
            reconnecting = c.playWhenReady && status == PlaybackService.STATUS_RECONNECTING,
            offline = c.playWhenReady && status == PlaybackService.STATUS_OFFLINE,
            info = streamInfo(),
        )
    }

    private fun streamInfo(): String {
        val quality = when (qualityTag) {
            "high" -> "ВЫС"
            "medium" -> "СРЕД"
            "low" -> "ЭКОН"
            else -> ""
        }
        return listOfNotNull(
            kbps.takeIf { it > 0 }?.let { "${it}K" },
            format.takeIf { it.isNotEmpty() }?.uppercase(),
            quality.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
    }

    /** Play [station] inside [playlist] so that prev/next buttons switch stations within the list. */
    fun play(station: Station, playlist: List<Station>) {
        val c = controller ?: return
        val list = playlist.filter { it.playable }
        val index = list.indexOfFirst { it.id == station.id }
        if (index < 0) return
        errorStationId = null
        c.setMediaItems(list.map { MediaItem.Builder().setMediaId(it.id).build() }, index, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        val u = _ui.value
        if (u.reconnecting || u.offline) {
            c.pause() // cancel the automatic reconnection
        } else if (c.playbackState == Player.STATE_IDLE && c.currentMediaItem != null) {
            errorStationId = null
            c.prepare(); c.play()
        } else if (c.playWhenReady) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    fun setEq(enabled: Boolean, gains: List<Float>) {
        container.eq.update(enabled, gains)
        viewModelScope.launch { container.prefs.saveEq(container.eq.state.value) }
    }

    fun setQuality(quality: QualitySetting) = updateSettings { it.copy(quality = quality) }

    fun setBuffer(buffer: BufferProfile) = updateSettings { it.copy(buffer = buffer) }

    private fun updateSettings(block: (Settings) -> Settings) {
        container.settings.update(block)
        viewModelScope.launch { container.prefs.saveSettings(container.settings.state.value) }
    }

    /** minutes = 0 turns the sleep timer off. */
    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) container.sleep.cancel() else container.sleep.start(minutes)
    }

    /** REC: start / stop saving the song that is playing. */
    fun toggleRecord() = container.recorder.toggle(_ui.value.stationName)

    fun setRegion(id: String) {
        viewModelScope.launch { container.prefs.saveRegion(id) }
    }

    fun toggleFavorite(stationId: String) {
        viewModelScope.launch { container.prefs.toggleFavorite(stationId) }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
