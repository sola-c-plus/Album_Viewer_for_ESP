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
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

class MediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaListenerService"
    }

    private var sessionManager: MediaSessionManager? = null
    private var monitorJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isUpdating = AtomicBoolean(false)

    private var currentTitle = ""
    private var currentArtist = ""
    private var isPlayingMusic = false

    private val sessionCallback = MediaSessionManager.OnActiveSessionsChangedListener {
        triggerUpdate()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, MediaListenerService::class.java)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionCallback, component)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register active sessions listener", e)
        }
        startRealtimeMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
        sessionManager?.removeOnActiveSessionsChangedListener(sessionCallback)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        triggerUpdate()
    }

    private fun startRealtimeMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(800)
                updateActiveMedia()
            }
        }
    }

    private fun triggerUpdate() {
        serviceScope.launch {
            updateActiveMedia()
        }
    }

    private suspend fun updateActiveMedia() {
        if (!isUpdating.compareAndSet(false, true)) return

        try {
            val trackInfo = detectCurrentActiveTrack()

            if (trackInfo != null && trackInfo.title.isNotEmpty()) {
                isPlayingMusic = true
                // ★曲が変わった時だけ1回だけ送信する（無限ループ送信を完全防止）
                val isNewTrack = (trackInfo.title != currentTitle || trackInfo.artist != currentArtist)

                if (isNewTrack) {
                    currentTitle = trackInfo.title
                    currentArtist = trackInfo.artist

                    val highResBmp = trackInfo.bitmap ?: createPlaceholderBitmap(240, 240)
                    val squareBmp = processBitmapToStandardBaseline(highResBmp, 240)
                    val jpegBytes = compressToBaselineJpeg(squareBmp, quality = 65)

                    withContext(Dispatchers.Main) {
                        MediaStateHolder.updateTrack(trackInfo.title, trackInfo.artist, "", squareBmp, jpegBytes)
                    }

                    BluetoothSppManager.sendMediaPacket(trackInfo.title, trackInfo.artist, "", jpegBytes)
                    Log.d(TAG, "Sent New Track ONCE: ${trackInfo.title} (${jpegBytes.size} bytes)")
                }
            } else {
                if (isPlayingMusic) {
                    isPlayingMusic = false
                    currentTitle = ""
                    currentArtist = ""
                }

                val cal = Calendar.getInstance()
                BluetoothSppManager.sendTimeSyncPacket(
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    cal.get(Calendar.SECOND)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateActiveMedia", e)
        } finally {
            isUpdating.set(false)
        }
    }

    data class TrackInfo(val title: String, val artist: String, val bitmap: Bitmap?)

    private fun detectCurrentActiveTrack(): TrackInfo? {
        try {
            val component = ComponentName(this, MediaListenerService::class.java)
            val controllers = sessionManager?.getActiveSessions(component) ?: emptyList()
            for (controller in controllers) {
                val metadata = controller.metadata ?: continue
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""

                if (title.isNotEmpty()) {
                    var bmp: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

                    if (bmp == null) {
                        val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                        if (!uriStr.isNullOrEmpty()) {
                            bmp = fetchBitmapFromUri(uriStr)
                        }
                    }

                    if (bmp == null) {
                        bmp = getNotificationHighResArt(title)
                    }

                    if (bmp == null) {
                        bmp = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                    }

                    return TrackInfo(title, artist, bmp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaSession check error", e)
        }

        val notifications = activeNotifications ?: return null
        for (sbn in notifications) {
            val notif = sbn.notification ?: continue
            val extras = notif.extras ?: continue
            val isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            val pkg = sbn.packageName.lowercase()

            if (isMedia || pkg.contains("spotify") || pkg.contains("youtube") || pkg.contains("music") || pkg.contains("audio")) {
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

                if (title.isNotEmpty()) {
                    val bmp = getHighResFromNotificationExtras(extras, notif)
                    return TrackInfo(title, artist, bmp)
                }
            }
        }

        return null
    }

    private fun getNotificationHighResArt(expectedTitle: String): Bitmap? {
        val notifications = activeNotifications ?: return null
        for (sbn in notifications) {
            val notif = sbn.notification ?: continue
            val extras = notif.extras ?: continue
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            if (title.contains(expectedTitle, ignoreCase = true) || expectedTitle.contains(title, ignoreCase = true)) {
                return getHighResFromNotificationExtras(extras, notif)
            }
        }
        return null
    }

    private fun getHighResFromNotificationExtras(extras: Bundle, notif: Notification): Bitmap? {
        if (extras.containsKey(Notification.EXTRA_PICTURE)) {
            val pic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable<Parcelable>(Notification.EXTRA_PICTURE) as? Bitmap
            }
            if (pic != null) return pic
        }

        val largeIcon = notif.getLargeIcon()
        if (largeIcon != null) {
            val bmp = largeIcon.loadDrawable(this)?.toBitmap()
            if (bmp != null) return bmp
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
                conn.instanceFollowRedirects = true
                conn.doInput = true
                conn.connect()
                conn.inputStream.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
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

    private fun processBitmapToStandardBaseline(source: Bitmap, targetSize: Int = 240): Bitmap {
        val minEdge = minOf(source.width, source.height)
        val cropX = (source.width - minEdge) / 2
        val cropY = (source.height - minEdge) / 2
        val cropped = Bitmap.createBitmap(source, cropX, cropY, minEdge, minEdge)
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)

        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return result
    }

    private fun compressToBaselineJpeg(bitmap: Bitmap, quality: Int = 65): ByteArray {
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