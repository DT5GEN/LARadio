package app.smradio.playback

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.smradio.App
import app.smradio.AppContainer
import app.smradio.data.BufferProfile
import app.smradio.data.QualitySetting
import app.smradio.data.Settings
import app.smradio.data.Station
import app.smradio.data.Stream
import app.smradio.playback.dsp.EqAudioProcessor
import app.smradio.playback.dsp.FftTapProcessor
import app.smradio.playback.dsp.SpectrumBus
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Background radio playback + media session (notification, lock screen, Bluetooth/steering-wheel
 * buttons, Android Auto browse tree: Genres -> Stations).
 *
 * Resilience (docs/CONCEPT.md 5.3, 5.4):
 *  - next stream of the same station on error or if not READY in time;
 *  - when every stream failed: wait for the network / retry with backoff, never give up on a station
 *    that has already played in this session (road use: tunnels, dead zones);
 *  - stall watchdog: a stream stuck in BUFFERING too long is reconnected;
 *  - AUTO quality steps down after repeated stalls;
 *  - buffer profile (start delay, retry count, stall timeout) comes from user settings.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var container: AppContainer
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var builtBuffer = BufferProfile.NORMAL
    private var appliedQuality = QualitySetting.AUTO
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // per-station playback state (reset by resetStationState)
    private var currentId: String? = null
    private var attempt = 0
    private var round = 0
    private var autoCap = 0
    private var wasReady = false
    private var playedOnce = false
    private var retryPending = false
    private var status = STATUS_OK
    private var errorStationId: String? = null
    private var infoKbps = 0
    private var infoFmt = ""
    private var infoQuality = ""
    private val stalls = ArrayDeque<Long>()

    private val settings: Settings get() = container.settings.state.value
    private val repo get() = container.catalogRepo

    private fun startTimeoutMs() = START_TIMEOUT_MS + builtBuffer.startMs

    private val startTimeout = Runnable {
        if (player.playWhenReady && player.playbackState != Player.STATE_READY) {
            Log.w(TAG, "stream start timeout for $currentId")
            onStreamFailed()
        }
    }

    private val stallRestart = Runnable {
        if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
            Log.w(TAG, "stalled too long, reconnecting $currentId")
            reconnect()
        }
    }

    private val retryRunnable = Runnable {
        retryPending = false
        runRetry()
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (SpectrumBus.appForeground && player.playWhenReady) {
                LiveStats.bufferedSec.value = (player.totalBufferedDuration / 1000).toInt().coerceAtLeast(0)
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (id != currentId) {
                currentId = id
                resetStationState()
                // re-resolve with the current quality preference (playlist URLs may be older)
                if (id != null) handler.post { applyBest() }
            }
            handler.removeCallbacks(startTimeout)
            if (id != null) handler.postDelayed(startTimeout, startTimeoutMs())
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    handler.removeCallbacks(startTimeout)
                    handler.removeCallbacks(stallRestart)
                    wasReady = true
                    playedOnce = true
                    round = 0
                    if (status != STATUS_OK || errorStationId != null) {
                        status = STATUS_OK
                        errorStationId = null
                        pushExtras()
                    }
                }
                Player.STATE_BUFFERING -> if (wasReady && player.playWhenReady) {
                    recordStall()
                    handler.removeCallbacks(stallRestart)
                    handler.postDelayed(stallRestart, builtBuffer.stallRestartMs)
                }
                else -> handler.removeCallbacks(stallRestart)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                // user paused (or sleep timer): stop all recovery work
                handler.removeCallbacks(retryRunnable)
                handler.removeCallbacks(stallRestart)
                handler.removeCallbacks(startTimeout)
                retryPending = false
                if (status != STATUS_OK) {
                    status = STATUS_OK
                    pushExtras()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "player error for $currentId: ${error.errorCodeName}")
            onStreamFailed()
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = (application as App).container
        builtBuffer = settings.buffer
        appliedQuality = settings.quality
        player = buildPlayer(builtBuffer)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
        netCallback = container.net.listen(handler) { onNetworkAvailable() }
        scope.launch { container.settings.state.collect { onSettingsChanged(it) } }
        scope.launch { container.sleep.endAtMs.collectLatest { end -> if (end != null) runSleepTimer(end) } }
        handler.post(statsTick)
    }

    /** Audio goes through: equalizer -> spectrum tap -> speaker. Buffer behaviour follows [profile]. */
    private fun buildPlayer(profile: BufferProfile): ExoPlayer {
        val dataSourceFactory = OkHttpDataSource.Factory(container.http).setUserAgent(AppContainer.USER_AGENT)
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(EqAudioProcessor(container.eq), FftTapProcessor()))
                .build()
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(profile.maxMs, profile.maxMs, profile.startMs, profile.rebufferMs)
            .setTargetBufferBytes(profile.maxBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(profile.retries))
        return ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { it.addListener(playerListener) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the user swipes the app away while listening (car use); otherwise stop.
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        netCallback?.let { container.net.stopListening(it) }
        scope.cancel()
        LiveStats.bufferedSec.value = 0
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- settings

    private fun onSettingsChanged(s: Settings) {
        if (s.buffer != builtBuffer) {
            builtBuffer = s.buffer
            rebuildPlayer()
        }
        if (s.quality != appliedQuality) {
            appliedQuality = s.quality
            autoCap = 0
            applyBest()
        }
    }

    /** Buffer profile changed: build a fresh ExoPlayer with the new LoadControl and carry the playlist over. */
    private fun rebuildPlayer() {
        val old = player
        val items = List(old.mediaItemCount) { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex.coerceAtLeast(0)
        val play = old.playWhenReady
        handler.removeCallbacks(startTimeout)
        handler.removeCallbacks(stallRestart)
        handler.removeCallbacks(retryRunnable)
        retryPending = false
        old.removeListener(playerListener)
        val fresh = buildPlayer(builtBuffer)
        if (items.isNotEmpty()) {
            fresh.setMediaItems(items, index, C.TIME_UNSET)
            fresh.prepare()
            fresh.playWhenReady = play
        }
        player = fresh
        session?.player = fresh
        old.release()
        attempt = 0
        round = 0
        wasReady = false
    }

    // ---------------------------------------------------------------- stream choice

    private fun effectiveQuality(): QualityPref = when (settings.quality) {
        QualitySetting.HIGH -> QualityPref.HIGH
        QualitySetting.MEDIUM -> QualityPref.MEDIUM
        QualitySetting.LOW -> QualityPref.LOW
        QualitySetting.AUTO -> {
            val net = container.net
            // slow mobile link -> start one step lower; stalls push further down (autoCap)
            val base = if (net.isCellular && net.downKbps in 1..2_999) 1 else 0
            QualityPref.values()[(base + autoCap).coerceAtMost(2)]
        }
    }

    private fun streamsFor(station: Station): List<Stream> = StreamSelector.order(station, effectiveQuality())

    private fun findStation(id: String?): Station? = repo.current.stations.find { it.id == id }

    private fun currentUrl(): String? = player.currentMediaItem?.localConfiguration?.uri?.toString()

    /** Switch to the best stream for the current preference if it differs from what is loaded. */
    private fun applyBest() {
        val station = findStation(player.currentMediaItem?.mediaId) ?: return
        val best = streamsFor(station).firstOrNull() ?: return
        if (currentUrl() != best.url) openStream(station, best) else publish(station, best)
    }

    /** (Re)open [stream] for the current playlist item: stop, swap URL if needed, prepare, play. */
    private fun openStream(station: Station, stream: Stream) {
        handler.removeCallbacks(startTimeout)
        handler.removeCallbacks(stallRestart)
        wasReady = false
        player.stop()
        if (currentUrl() != stream.url) {
            player.replaceMediaItem(player.currentMediaItemIndex, station.toMediaItem(stream.url))
        }
        player.prepare()
        player.play()
        publish(station, stream)
        handler.postDelayed(startTimeout, startTimeoutMs())
    }

    private fun publish(station: Station, stream: Stream) {
        infoKbps = stream.bitrate ?: 0
        infoFmt = stream.format
        infoQuality = stream.quality
        pushExtras()
    }

    private fun pushExtras() {
        session?.setSessionExtras(Bundle().apply {
            putString(EXTRA_ERROR_STATION, errorStationId)
            putString(EXTRA_STATUS, status)
            putInt(EXTRA_KBPS, infoKbps)
            putString(EXTRA_FORMAT, infoFmt)
            putString(EXTRA_QUALITY, infoQuality)
        })
    }

    private fun resetStationState() {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(stallRestart)
        retryPending = false
        attempt = 0
        round = 0
        autoCap = 0
        wasReady = false
        playedOnce = false
        stalls.clear()
        status = STATUS_OK
        errorStationId = null
        infoKbps = 0
        infoFmt = ""
        infoQuality = ""
        pushExtras()
    }

    // ---------------------------------------------------------------- recovery

    /** The current stream failed: next stream, then retry rounds with backoff, then wait for network. */
    private fun onStreamFailed() {
        handler.removeCallbacks(startTimeout)
        handler.removeCallbacks(stallRestart)
        if (!player.playWhenReady) return
        val station = findStation(player.currentMediaItem?.mediaId) ?: return
        val ordered = streamsFor(station)
        attempt++
        if (attempt < ordered.size) {
            Log.i(TAG, "fallback #$attempt for ${station.id}: ${ordered[attempt].url}")
            openStream(station, ordered[attempt])
            return
        }
        // every stream of this station failed in this round
        attempt = 0
        player.stop()
        if (!container.net.isOnline) {
            Log.i(TAG, "offline, waiting for network")
            status = STATUS_OFFLINE
            pushExtras()
            scheduleRetry(OFFLINE_POLL_MS)
            return
        }
        round++
        if (!playedOnce && round >= MAX_ROUNDS_NEVER_PLAYED) {
            Log.w(TAG, "all streams failed for ${station.id}")
            round = 0
            status = STATUS_OK
            errorStationId = station.id
            pushExtras()
            return
        }
        status = STATUS_RECONNECTING
        pushExtras()
        scheduleRetry(BACKOFF_MS[(round - 1).coerceIn(0, BACKOFF_MS.lastIndex)])
    }

    private fun scheduleRetry(delayMs: Long) {
        retryPending = true
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    private fun runRetry() {
        if (!player.playWhenReady) return
        val station = findStation(player.currentMediaItem?.mediaId) ?: return
        if (!container.net.isOnline) {
            scheduleRetry(OFFLINE_POLL_MS)
            return
        }
        val first = streamsFor(station).firstOrNull() ?: return
        openStream(station, first)
    }

    /** Re-open the stream that is currently loaded (dead socket, network handover). */
    private fun reconnect() {
        val station = findStation(player.currentMediaItem?.mediaId) ?: return
        if (!container.net.isOnline) {
            player.stop()
            status = STATUS_OFFLINE
            pushExtras()
            scheduleRetry(OFFLINE_POLL_MS)
            return
        }
        val stream = station.streams.find { it.url == currentUrl() } ?: streamsFor(station).firstOrNull() ?: return
        status = STATUS_RECONNECTING
        pushExtras()
        openStream(station, stream)
    }

    private fun onNetworkAvailable() {
        if (!player.playWhenReady || player.mediaItemCount == 0) return
        when {
            retryPending -> {
                handler.removeCallbacks(retryRunnable)
                retryPending = false
                runRetry()
            }
            wasReady && player.playbackState == Player.STATE_BUFFERING -> reconnect()
        }
    }

    /** Two stalls within two minutes on AUTO quality: step down to a lighter stream (if the station has one). */
    private fun recordStall() {
        val now = System.currentTimeMillis()
        stalls.addLast(now)
        while (stalls.isNotEmpty() && now - stalls.first() > STALL_WINDOW_MS) stalls.removeFirst()
        if (settings.quality == QualitySetting.AUTO && stalls.size >= 2 && autoCap < 2) {
            stalls.clear()
            autoCap++
            Log.i(TAG, "frequent stalls, AUTO quality steps down (cap=$autoCap)")
            handler.post { applyBest() }
        }
    }

    // ---------------------------------------------------------------- sleep timer

    private suspend fun runSleepTimer(endAtMs: Long) {
        try {
            while (true) {
                val left = endAtMs - System.currentTimeMillis()
                if (left <= 0) break
                // gentle fade over the last 15 s
                player.volume = if (left <= FADE_MS) (left.toFloat() / FADE_MS).coerceIn(0f, 1f) else 1f
                delay(if (left > FADE_MS + 1_000) minOf(left - FADE_MS, 30_000L) else 250L)
            }
            player.pause()
            container.sleep.cancel()
        } finally {
            player.volume = 1f
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val catalog = repo.current
            val children: List<MediaItem> = when {
                parentId == ROOT_ID -> catalog.genres.sortedBy { it.order }.map { it.toFolderItem() }
                parentId.startsWith(GENRE_PREFIX) -> {
                    val genreId = parentId.removePrefix(GENRE_PREFIX)
                    catalog.stations.filter { genreId in it.genres && it.playable }.map { it.toMediaItem(null) }
                }
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val station = repo.current.stations.find { it.id == mediaId }
            return Futures.immediateFuture(
                if (station != null) LibraryResult.ofItem(station.toMediaItem(null), null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        /** Controllers send items with only a mediaId; the service picks the real stream URL. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            resetStationState()
            val quality = effectiveQuality()
            val stations = repo.current.stations
            val resolved = mediaItems.mapNotNull { item ->
                val station = stations.find { it.id == item.mediaId } ?: return@mapNotNull null
                val url = StreamSelector.order(station, quality).firstOrNull()?.url ?: return@mapNotNull null
                station.toMediaItem(url)
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val START_TIMEOUT_MS = 8_000L
        private const val MAX_ROUNDS_NEVER_PLAYED = 3
        private val BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L)
        private const val OFFLINE_POLL_MS = 10_000L
        private const val STALL_WINDOW_MS = 120_000L
        private const val FADE_MS = 15_000L

        const val EXTRA_ERROR_STATION = "errorStation"
        const val EXTRA_STATUS = "status"
        const val EXTRA_KBPS = "kbps"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_QUALITY = "quality"
        const val STATUS_OK = "ok"
        const val STATUS_RECONNECTING = "reconnecting"
        const val STATUS_OFFLINE = "offline"
    }
}
