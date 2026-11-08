package app.smradio.playback.rec

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/** What the UI shows about recording. */
data class RecState(
    val recording: Boolean = false,
    val title: String? = null,
    val seconds: Int = 0,
    /** A recordable stream (MP3/AAC/OGG over HTTP) is currently flowing. */
    val canRecord: Boolean = false,
    /** Short result text ("Сохранено: ...") shown for a few seconds. */
    val message: String? = null,
)

/**
 * "Record this song" (docs/CONCEPT.md 5.9a).
 *
 * [Tap] sits between OkHttp and ExoPlayer ([IcyTeeDataSource]) and sees the raw stream bytes. It strips the ICY
 * metadata blocks (StreamTitle) out of an audio-only copy that goes into a rolling buffer of the last
 * [BUFFER_MS] milliseconds, together with "track changed here" markers. REC saves the song that is being heard,
 * from its beginning when it is still inside the buffer, as raw MP3/AAC/OGG (no re-encoding) into Music/SantaMonicaRadio.
 * Everything here is best-effort: any failure is swallowed so that playback is never affected.
 */
@UnstableApi
class TrackRecorder(private val context: Context) {

    private enum class Fmt(val ext: String, val mime: String) {
        MP3("mp3", "audio/mpeg"), AAC("aac", "audio/aac"), OGG("ogg", "audio/ogg")
    }

    private class Chunk(val data: ByteArray, val startAbs: Long, val timeMs: Long) { var len = 0 }
    private class Marker(val absPos: Long, val title: String, val fromStart: Boolean)

    private inner class Active(
        val out: OutputStream,
        val title: String?,
        val partial: Boolean,
        val autoStop: Boolean,
        val startedAt: Long,
        val close: (keep: Boolean) -> String?,
    ) {
        var bytes = 0L
        fun write(b: ByteArray, off: Int, n: Int) { out.write(b, off, n); bytes += n }
    }

    val state: StateFlow<RecState> get() = _state
    private val _state = MutableStateFlow(RecState())

    /** Seconds of audio the player has buffered ahead (set by PlaybackService every second). */
    @Volatile var aheadMs: Long = 0

    val tap = Tap()

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val chunks = ArrayDeque<Chunk>()
    private val markers = ArrayDeque<Marker>()
    private var totalAbs = 0L
    private var fmt: Fmt? = null
    private var metaint = 0
    private var icyBitrate = 0
    private var icyName: String? = null
    private var currentTitle: String? = null
    private var sawTitle = false
    private var active: Active? = null
    private var message: String? = null
    private var lastPublish = 0L

    // ICY parser state
    private var phase = 0 // 0 audio, 1 length byte, 2 metadata
    private var audioLeft = 0
    private var metaLeft = 0
    private val metaBuf = ByteArrayOutputStream()

    // ------------------------------------------------------------------ public API

    fun toggle(stationName: String?) {
        io.execute {
            try {
                synchronized(lock) { if (active != null) finish() else begin(stationName ?: icyName) }
            } catch (t: Throwable) {
                Log.w(TAG, "toggle failed", t)
            }
        }
    }

    /** The user switched station: forget the buffer, save whatever is being recorded. */
    fun onStationChanged() {
        try {
            synchronized(lock) {
                if (active != null) finish()
                chunks.clear()
                markers.clear()
                currentTitle = null
                sawTitle = false
                publish(force = true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onStationChanged failed", t)
        }
    }

    // ------------------------------------------------------------------ tap (loader thread)

    inner class Tap {
        fun onOpen(headers: Map<String, List<String>>) = guard {
            synchronized(lock) {
                if (active != null) finish() // reconnect: audio is no longer contiguous
                fun h(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
                val ct = (h("content-type") ?: "").lowercase()
                fmt = when {
                    ct.contains("mpeg") || ct.contains("mp3") -> Fmt.MP3
                    ct.contains("aac") -> Fmt.AAC
                    ct.contains("ogg") -> Fmt.OGG
                    else -> null // playlists, HLS manifests, video segments...
                }
                metaint = h("icy-metaint")?.toIntOrNull() ?: 0
                icyBitrate = h("icy-br")?.substringBefore(',')?.trim()?.toIntOrNull() ?: 0
                icyName = h("icy-name")?.trim()?.takeIf { it.isNotEmpty() }
                phase = 0
                audioLeft = metaint
                metaBuf.reset()
                publish(force = true)
            }
        }

        fun onBytes(b: ByteArray, off: Int, n: Int) = guard {
            if (fmt == null) return@guard
            synchronized(lock) { consume(b, off, n) }
        }

        private inline fun guard(block: () -> Unit) {
            try { block() } catch (t: Throwable) { Log.w(TAG, "tap error", t) }
        }
    }

    // ------------------------------------------------------------------ stream parsing (under lock)

    private fun consume(b: ByteArray, off: Int, n: Int) {
        var i = off
        val end = off + n
        while (i < end) {
            if (metaint <= 0) {
                appendAudio(b, i, end - i)
                break
            }
            when (phase) {
                0 -> {
                    val take = min(end - i, audioLeft)
                    appendAudio(b, i, take)
                    i += take
                    audioLeft -= take
                    if (audioLeft == 0) phase = 1
                }
                1 -> {
                    metaLeft = (b[i].toInt() and 0xFF) * 16
                    i++
                    if (metaLeft == 0) { phase = 0; audioLeft = metaint } else { phase = 2; metaBuf.reset() }
                }
                else -> {
                    val take = min(end - i, metaLeft)
                    metaBuf.write(b, i, take)
                    i += take
                    metaLeft -= take
                    if (metaLeft == 0) {
                        onMetadata(metaBuf.toByteArray())
                        phase = 0
                        audioLeft = metaint
                    }
                }
            }
        }
        publish(force = false)
    }

    private fun onMetadata(raw: ByteArray) {
        var text = String(raw, Charsets.UTF_8)
        if (text.contains('�')) text = String(raw, Charsets.ISO_8859_1)
        text = text.trimEnd('\u0000')
        val title = Regex("StreamTitle='(.*?)';", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()
            ?: Regex("StreamTitle='(.*)'").find(text)?.groupValues?.get(1)?.trim()
        if (title.isNullOrEmpty() || title == currentTitle) return
        currentTitle = title
        // the first title of a connection is "whatever is playing now": it is not a real song start
        markers.addLast(Marker(totalAbs, title, fromStart = sawTitle))
        sawTitle = true
        active?.let { if (it.autoStop) finish() }
    }

    private fun appendAudio(b: ByteArray, off: Int, n: Int) {
        if (n <= 0) return
        val now = SystemClock.elapsedRealtime()
        var o = off
        var left = n
        while (left > 0) {
            var c = chunks.lastOrNull()
            if (c == null || c.len == c.data.size) {
                c = Chunk(ByteArray(CHUNK), totalAbs, now)
                chunks.addLast(c)
            }
            val take = min(left, c.data.size - c.len)
            System.arraycopy(b, o, c.data, c.len, take)
            c.len += take
            o += take
            left -= take
            totalAbs += take
        }
        active?.let {
            try { it.write(b, off, n) } catch (e: Exception) { Log.w(TAG, "write failed", e); finish() }
        }
        // prune by age and size
        while (chunks.size > 1 && (now - chunks.first().timeMs > BUFFER_MS || totalAbs - chunks.first().startAbs > MAX_BYTES)) {
            chunks.removeFirst()
        }
        val start = chunks.firstOrNull()?.startAbs ?: totalAbs
        while (markers.size >= 2 && markers[1].absPos <= start) markers.removeFirst()
    }

    // ------------------------------------------------------------------ recording (under lock)

    private fun bytesPerSec(): Double {
        val now = SystemClock.elapsedRealtime()
        for (c in chunks.reversed()) {
            if (now - c.timeMs >= 30_000) return (totalAbs - c.startAbs) / ((now - c.timeMs) / 1000.0)
        }
        return if (icyBitrate > 0) icyBitrate * 125.0 else 16_000.0
    }

    private fun begin(station: String?) {
        val f = fmt
        if (f == null || chunks.isEmpty()) return say("Сейчас нечего записывать")
        val bufferStart = chunks.first().startAbs
        // where the listener is: what we received minus what the player still holds in its buffer
        val playAbs = (totalAbs - (aheadMs / 1000.0 * bytesPerSec()).toLong()).coerceIn(bufferStart, totalAbs)
        val cur = markers.lastOrNull { it.absPos <= playAbs }
        val next = markers.firstOrNull { it.absPos > playAbs }
        val startAbs = (cur?.absPos ?: playAbs).coerceAtLeast(bufferStart)
        val title = cur?.title ?: currentTitle
        val partial = cur == null || !cur.fromStart
        val endAbs = if (cur != null && next != null) next.absPos else null

        val sink = openSink(title, station, f) ?: return say("Не удалось создать файл")
        val a = Active(sink.first, title, partial, autoStop = cur != null, startedAt = SystemClock.elapsedRealtime(), close = sink.second)
        // backfill from the rolling buffer
        val stop = endAbs ?: totalAbs
        for (c in chunks) {
            val cEnd = c.startAbs + c.len
            if (cEnd <= startAbs || c.startAbs >= stop) continue
            val from = (maxOf(startAbs, c.startAbs) - c.startAbs).toInt()
            val to = (minOf(stop, cEnd) - c.startAbs).toInt()
            a.write(c.data, from, to - from)
        }
        active = a
        if (endAbs != null) finish() else publish(force = true)
    }

    private fun finish() {
        val a = active ?: return
        active = null
        val tooShort = a.bytes < MIN_BYTES
        val name = try { a.close(!tooShort) } catch (t: Throwable) { Log.w(TAG, "close failed", t); null }
        say(
            when {
                tooShort -> "Слишком коротко — не сохранено"
                name == null -> "Не удалось сохранить"
                a.partial -> "Сохранено (не с начала): $name"
                else -> "Сохранено: $name"
            }
        )
    }

    private fun say(text: String) {
        message = text
        publish(force = true)
        main.postDelayed({ if (message == text) { message = null; publish(force = true) } }, 8_000)
    }

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublish < 1_000) return
        lastPublish = now
        val a = active
        _state.value = RecState(
            recording = a != null,
            title = a?.title ?: currentTitle,
            seconds = if (a != null) ((now - a.startedAt) / 1000).toInt() else 0,
            canRecord = fmt != null && chunks.isNotEmpty(),
            message = message,
        )
    }

    // ------------------------------------------------------------------ output file

    private fun openSink(title: String?, station: String?, f: Fmt): Pair<OutputStream, (Boolean) -> String?>? {
        val artist = title?.substringBefore(" - ", "")?.takeIf { it.isNotEmpty() && title.contains(" - ") }
        val song = if (artist != null) title.substringAfter(" - ") else title
        val base = sanitize(title ?: "${station ?: "Radio"} ${SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())}")
        val fileName = "$base.${f.ext}"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, f.mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/SantaMonicaRadio")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                    put(MediaStore.Audio.Media.TITLE, song ?: base)
                    if (artist != null) put(MediaStore.Audio.Media.ARTIST, artist)
                    if (station != null) put(MediaStore.Audio.Media.ALBUM, station)
                }
                val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                val raw = resolver.openOutputStream(uri) ?: return null
                val out = BufferedOutputStream(raw, 64 * 1024)
                if (f == Fmt.MP3) out.write(id3(song ?: base, artist, station))
                Pair(out) { keep ->
                    try { out.close() } catch (_: Exception) { }
                    if (keep) {
                        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                        fileName
                    } else {
                        resolver.delete(uri, null, null)
                        null
                    }
                }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "SantaMonicaRadio").apply { mkdirs() }
                var file = File(dir, fileName)
                var n = 2
                while (file.exists()) file = File(dir, "$base ($n).${f.ext}").also { n++ }
                val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
                if (f == Fmt.MP3) out.write(id3(song ?: base, artist, station))
                Pair(out) { keep ->
                    try { out.close() } catch (_: Exception) { }
                    if (keep) file.name else { file.delete(); null }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "openSink failed", t)
            null
        }
    }

    private fun sanitize(s: String) =
        s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(100).ifEmpty { "radio" }

    /** Minimal ID3v2.3 tag (UTF-16 text frames) so players show title / artist / station. */
    private fun id3(title: String, artist: String?, album: String?): ByteArray {
        fun frame(id: String, text: String): ByteArray {
            val t = text.toByteArray(Charsets.UTF_16LE)
            val payloadSize = 3 + t.size
            val o = ByteArrayOutputStream()
            o.write(id.toByteArray(Charsets.US_ASCII))
            o.write(byteArrayOf((payloadSize ushr 24).toByte(), (payloadSize ushr 16).toByte(), (payloadSize ushr 8).toByte(), payloadSize.toByte()))
            o.write(byteArrayOf(0, 0))
            o.write(byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()))
            o.write(t)
            return o.toByteArray()
        }
        val frames = ByteArrayOutputStream().apply {
            write(frame("TIT2", title))
            if (artist != null) write(frame("TPE1", artist))
            if (album != null) write(frame("TALB", album))
        }.toByteArray()
        val size = frames.size
        val tag = ByteArrayOutputStream()
        tag.write("ID3".toByteArray(Charsets.US_ASCII))
        tag.write(byteArrayOf(3, 0, 0))
        tag.write(byteArrayOf(((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(), ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte()))
        tag.write(frames)
        return tag.toByteArray()
    }

    companion object {
        private const val TAG = "TrackRecorder"
        private const val CHUNK = 32 * 1024
        private const val BUFFER_MS = 15 * 60 * 1000L
        private const val MAX_BYTES = 40L * 1024 * 1024
        private const val MIN_BYTES = 150_000L
    }
}

/** Passes everything through unchanged and shows the raw bytes to [TrackRecorder.Tap]. */
@UnstableApi
class IcyTeeDataSource(private val upstream: DataSource, private val tap: TrackRecorder.Tap) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        tap.onOpen(upstream.responseHeaders)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) tap.onBytes(buffer, offset, n)
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
