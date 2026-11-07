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
import app.smradio.data.Station
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
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as App).container

    val catalog = container.catalogRepo.catalog
    val favorites = container.prefs.favorites
    val eq: StateFlow<EqState> = container.eq.state

    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var errorStationId: String? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync()
    }

    private val sessionListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            errorStationId = extras.getString("errorStation")
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
        )
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
        if (c.playbackState == Player.STATE_IDLE && c.currentMediaItem != null) {
            c.prepare(); c.play()
        } else if (c.playWhenReady) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    fun setEq(enabled: Boolean, gains: List<Float>) {
        container.eq.update(enabled, gains)
        viewModelScope.launch { container.prefs.saveEq(container.eq.state.value) }
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
