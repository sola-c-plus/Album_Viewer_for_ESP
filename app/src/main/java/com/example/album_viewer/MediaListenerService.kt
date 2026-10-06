package com.example.album_viewer

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

class MediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaListenerService"
    }

    private var sessionManager: MediaSessionManager? = null
    private var monitorJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var currentTitle = ""
    private var currentArtist = ""
    private var lastSentImageHash = 0
    private var isPlayingMusic = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        startRealtimeMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
    }

    private fun startRealtimeMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(300)

                try {
                    val trackInfo = detectCurrentActiveTrack()

                    if (trackInfo != null && trackInfo.title.isNotEmpty()) {
                        isPlayingMusic = true
                        val isNewTrack = (trackInfo.title != currentTitle || trackInfo.artist != currentArtist)

                        val bitmap = trackInfo.bitmap
                        val bmpHash = bitmap?.generationId ?: 0

                        if (isNewTrack || (bmpHash != 0 && bmpHash != lastSentImageHash)) {
                            currentTitle = trackInfo.title
                            currentArtist = trackInfo.artist
                            lastSentImageHash = bmpHash

                            val finalBmp = bitmap ?: createPlaceholderBitmap(240, 240)
                            val cleanBmp = processBitmapToStandardSquare(finalBmp, 240)
                            // ★品質55% (約6〜8KBに軽量化し、ESP32のバッファに余裕で収める！)
                            val jpegBytes = compressToStandardJpeg(cleanBmp, quality = 55)

                            val displayBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                            withContext(Dispatchers.Main) {
                                MediaStateHolder.updateTrack(trackInfo.title, trackInfo.artist, "", displayBmp, jpegBytes)
                            }

                            BluetoothSppManager.sendMediaPacket(trackInfo.title, trackInfo.artist, "", jpegBytes)
                            Log.d(TAG, "Sent Track: ${trackInfo.title} (${jpegBytes.size} bytes)")
                        }
                    } else {
                        if (isPlayingMusic) {
                            isPlayingMusic = false
                            currentTitle = ""
                            currentArtist = ""
                            lastSentImageHash = 0
                        }

                        val cal = Calendar.getInstance()
                        BluetoothSppManager.sendTimeSyncPacket(
                            cal.get(Calendar.HOUR_OF_DAY),
                            cal.get(Calendar.MINUTE),
                            cal.get(Calendar.SECOND)
                        )
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    data class TrackInfo(val title: String, val artist: String, val bitmap: Bitmap?)

    private fun detectCurrentActiveTrack(): TrackInfo? {
        // 1. オリジナル高解像度Bitmapを最優先
        try {
            val component = ComponentName(this, MediaListenerService::class.java)
            val controllers = sessionManager?.getActiveSessions(component) ?: emptyList()
            val controller = controllers.firstOrNull()
            val metadata = controller?.metadata
            if (metadata != null) {
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                
                var bmp = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

                if (bmp == null) {
                    val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                        ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    if (!uriStr.isNullOrEmpty()) {
                        bmp = fetchBitmapFromUri(uriStr)
                    }
                }

                if (title.isNotEmpty()) {
                    if (bmp == null) {
                        bmp = getNotificationLargeIconFallback(title)
                    }
                    return TrackInfo(title, artist, bmp)
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        // 2. 通知から検出
        val notifications = activeNotifications ?: return null
        for (sbn in notifications) {
            val notif = sbn.notification ?: continue
            val extras = notif.extras ?: continue
            val isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            val pkg = sbn.packageName.lowercase()

            if (isMedia || pkg.contains("youtube") || pkg.contains("spotify") || pkg.contains("music")) {
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                val icon = notif.getLargeIcon()
                val bmp = icon?.loadDrawable(this)?.toBitmap()

                if (title.isNotEmpty()) {
                    return TrackInfo(title, artist, bmp)
                }
            }
        }

        return null
    }

    private fun getNotificationLargeIconFallback(expectedTitle: String): Bitmap? {
        val notifications = activeNotifications ?: return null
        for (sbn in notifications) {
            val notif = sbn.notification ?: continue
            val extras = notif.extras ?: continue
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            if (title.contains(expectedTitle, ignoreCase = true) || expectedTitle.contains(title, ignoreCase = true)) {
                return notif.getLargeIcon()?.loadDrawable(this)?.toBitmap()
            }
        }
        return null
    }

    private fun fetchBitmapFromUri(uriStr: String): Bitmap? {
        return try {
            if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                val url = URL(uriStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doInput = true
                conn.connect()
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } else {
                val uri = Uri.parse(uriStr)
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun processBitmapToStandardSquare(source: Bitmap, targetSize: Int = 240): Bitmap {
        val minEdge = minOf(source.width, source.height)
        val cropX = (source.width - minEdge) / 2
        val cropY = (source.height - minEdge) / 2
        val cropped = Bitmap.createBitmap(source, cropX, cropY, minEdge, minEdge)
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)

        val cleanBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(cleanBmp)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(scaled, 0f, 0f, null)
        return cleanBmp
    }

    private fun compressToStandardJpeg(bitmap: Bitmap, quality: Int = 55): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun createPlaceholderBitmap(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)
        return bmp
    }
}