package app.smradio.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.smradio.data.Genre
import app.smradio.data.Station

const val ROOT_ID = "root"
const val GENRE_PREFIX = "genre:"

/**
 * title and artist both hold the station name. When the stream sends ICY metadata,
 * ExoPlayer replaces the title with the current track, so "title != artist" means
 * "now playing info is available".
 */
fun Station.toMediaItem(streamUrl: String?): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(name)
        .setArtist(name)
        .setAlbumTitle(listOfNotNull(frequency, description).joinToString(" · "))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setExtras(Bundle().apply { putString("stationId", id) })
        .build()
    val builder = MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata)
    if (streamUrl != null) builder.setUri(streamUrl)
    return builder.build()
}

fun Genre.toFolderItem(): MediaItem = MediaItem.Builder()
    .setMediaId(GENRE_PREFIX + id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(nameRu)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
            .build()
    )
    .build()

fun rootItem(): MediaItem = MediaItem.Builder()
    .setMediaId(ROOT_ID)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle("Santa Monica Radio")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
    )
    .build()
