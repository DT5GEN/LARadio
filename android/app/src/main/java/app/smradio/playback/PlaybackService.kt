package app.smradio.playback

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.smradio.App
import app.smradio.AppContainer
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Background radio playback + media session (notification, lock screen, Bluetooth/steering-wheel
 * buttons, Android Auto browse tree: Genres -> Stations).
 *
 * Fallback logic (docs/CONCEPT.md 5.3): if a stream errors or does not start within 8 s,
 * the next stream of the same station is tried; when all fail, session extra "errorStation" is set.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private var currentId: String? = null
    private var attempt = 0
    private val quality = QualityPref.HIGH // user setting arrives in phase 4

    private val repo get() = (application as App).container.catalogRepo

    private val startTimeout = Runnable {
        if (player.playWhenReady && player.playbackState != Player.STATE_READY) {
            Log.w(TAG, "stream start timeout for $currentId")
            advance()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (id != currentId) {
                currentId = id
                attempt = 0
                session?.setSessionExtras(Bundle.EMPTY)
            }
            handler.removeCallbacks(startTimeout)
            if (id != null) handler.postDelayed(startTimeout, START_TIMEOUT_MS)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) handler.removeCallbacks(startTimeout)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "player error for $currentId: ${error.errorCodeName}")
            advance()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val container = (application as App).container
        val dataSourceFactory = OkHttpDataSource.Factory(container.http).setUserAgent(AppContainer.USER_AGENT)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
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
        player.addListener(playerListener)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the user swipes the app away while listening (car use); otherwise stop.
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(startTimeout)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Try the next stream of the current station, or report failure. */
    private fun advance() {
        handler.removeCallbacks(startTimeout)
        val item = player.currentMediaItem ?: return
        val station = repo.current.stations.find { it.id == item.mediaId } ?: return
        val ordered = StreamSelector.order(station, quality)
        attempt++
        if (attempt >= ordered.size) {
            Log.w(TAG, "all streams failed for ${station.id}")
            player.stop()
            session?.setSessionExtras(Bundle().apply { putString(EXTRA_ERROR_STATION, station.id) })
            return
        }
        Log.i(TAG, "fallback #$attempt for ${station.id}: ${ordered[attempt].url}")
        player.replaceMediaItem(player.currentMediaItemIndex, station.toMediaItem(ordered[attempt].url))
        player.prepare()
        player.play()
        handler.postDelayed(startTimeout, START_TIMEOUT_MS)
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
        const val EXTRA_ERROR_STATION = "errorStation"
    }
}
