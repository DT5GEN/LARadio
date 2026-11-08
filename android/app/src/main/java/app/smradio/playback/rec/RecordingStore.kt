package app.smradio.playback.rec

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** One saved song. Tags (station, genres) are what the "Записи" screen filters by. */
@Serializable
data class Recording(
    val id: String,
    val uri: String,
    val fileName: String,
    val title: String,
    val artist: String? = null,
    val stationId: String? = null,
    val stationName: String = "",
    val genres: List<String> = emptyList(),
    val timeMs: Long,
    val sizeBytes: Long,
    val durationSec: Int,
    /** true when the song was already running and was saved from the middle */
    val partial: Boolean = false,
)

/** Index of recordings (filesDir/recordings.json); the audio files themselves live in Music/SantaMonicaRadio. */
class RecordingStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Recording.serializer())
    private val file = File(context.filesDir, "recordings.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Recording>> get() = _items

    private fun load(): List<Recording> = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (t: Throwable) {
        Log.w(TAG, "load failed", t)
        emptyList()
    }

    @Synchronized
    private fun save(list: List<Recording>) {
        _items.value = list
        try { file.writeText(json.encodeToString(serializer, list)) } catch (t: Throwable) { Log.w(TAG, "save failed", t) }
    }

    @Synchronized
    fun add(r: Recording) = save(listOf(r) + _items.value)

    @Synchronized
    fun remove(id: String) {
        val r = _items.value.find { it.id == id } ?: return
        deleteFile(r)
        save(_items.value.filter { it.id != id })
    }

    /** Drops entries whose file was deleted outside the app. Call off the main thread. */
    @Synchronized
    fun prune() {
        val alive = _items.value.filter { exists(it) }
        if (alive.size != _items.value.size) save(alive)
    }

    private fun exists(r: Recording): Boolean = try {
        val uri = Uri.parse(r.uri)
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
        } else {
            File(uri.path ?: "").exists()
        }
    } catch (t: Throwable) { false }

    private fun deleteFile(r: Recording) {
        try {
            val uri = Uri.parse(r.uri)
            if (uri.scheme == "content") context.contentResolver.delete(uri, null, null) else File(uri.path ?: "").delete()
        } catch (t: Throwable) {
            Log.w(TAG, "delete failed", t)
        }
    }

    private companion object { const val TAG = "RecordingStore" }
}

data class RecPlayState(
    val id: String? = null,
    val playing: Boolean = false,
    val posMs: Int = 0,
    val durMs: Int = 0,
)

/** Plays saved recordings (separate from the station player; starting one pauses the radio via [onStarted]). */
class RecPlayer(private val context: Context) {
    private val _state = MutableStateFlow(RecPlayState())
    val state: StateFlow<RecPlayState> get() = _state

    var onStarted: (() -> Unit)? = null

    private var mp: MediaPlayer? = null
    private var queue: List<Recording> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val p = mp ?: return
            try {
                _state.value = _state.value.copy(playing = p.isPlaying, posMs = p.currentPosition, durMs = p.duration.coerceAtLeast(0))
            } catch (_: Exception) { }
            handler.postDelayed(this, 500)
        }
    }

    fun play(r: Recording, list: List<Recording>) {
        release()
        queue = list
        onStarted?.invoke()
        try {
            val p = MediaPlayer()
            p.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            p.setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            p.setDataSource(context, Uri.parse(r.uri))
            p.setOnPreparedListener {
                it.start()
                _state.value = RecPlayState(r.id, true, 0, it.duration.coerceAtLeast(0))
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            p.setOnCompletionListener { step(+1, autoStop = true) }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            mp = p
            _state.value = RecPlayState(r.id, true, 0, 0)
            p.prepareAsync()
        } catch (t: Throwable) {
            Log.w("RecPlayer", "play failed", t)
            stop()
        }
    }

    fun toggle() {
        val p = mp ?: return
        try {
            if (p.isPlaying) p.pause() else { onStarted?.invoke(); p.start() }
            _state.value = _state.value.copy(playing = p.isPlaying)
        } catch (_: Exception) { }
    }

    fun seekFraction(f: Float) {
        val p = mp ?: return
        try {
            val d = p.duration
            if (d > 0) p.seekTo((d * f.coerceIn(0f, 1f)).toInt())
        } catch (_: Exception) { }
    }

    fun next() = step(+1, autoStop = false)
    fun previous() = step(-1, autoStop = false)

    private fun step(delta: Int, autoStop: Boolean) {
        val cur = _state.value.id
        val i = queue.indexOfFirst { it.id == cur }
        val target = queue.getOrNull(if (i < 0) 0 else i + delta)
        if (target != null) play(target, queue) else if (autoStop) stop()
    }

    fun stop() {
        release()
        _state.value = RecPlayState()
    }

    private fun release() {
        handler.removeCallbacks(ticker)
        mp?.let { try { it.release() } catch (_: Exception) { } }
        mp = null
    }
}
