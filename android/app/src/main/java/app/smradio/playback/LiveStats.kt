package app.smradio.playback

import kotlinx.coroutines.flow.MutableStateFlow

/** Live numbers for the UI, written by PlaybackService (same process), read by the player screen. */
object LiveStats {
    /** Seconds of audio buffered ahead of the playback position. */
    val bufferedSec = MutableStateFlow(0)
}
