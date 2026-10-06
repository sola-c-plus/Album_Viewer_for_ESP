package com.example.album_viewer

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaTrackInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val bitmap: Bitmap? = null,
    val jpegBytes: ByteArray? = null
)

object MediaStateHolder {
    private val _currentTrack = MutableStateFlow(MediaTrackInfo())
    val currentTrack: StateFlow<MediaTrackInfo> = _currentTrack.asStateFlow()

    fun updateTrack(title: String, artist: String, album: String, bitmap: Bitmap?, jpegBytes: ByteArray?) {
        _currentTrack.value = MediaTrackInfo(title, artist, album, bitmap, jpegBytes)
    }
}