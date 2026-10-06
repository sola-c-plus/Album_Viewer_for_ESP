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

class MediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaListenerService"
    }

    private var sessionManager: MediaSessionManager? = null
    private var monitorJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var currentTitle = ""
    private var currentArtist = ""
    private var currentImageHash = 0

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        startMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorJob?.cancel()
    }

    /**
     * 250ms周期で監視し、画像が遅れて届いても自動で追従送信するシステム
     */
    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(250)

                try {
                    val component = ComponentName(this@MediaListenerService, MediaListenerService::class.java)
                    val controllers = sessionManager?.getActiveSessions(component) ?: emptyList()
                    val controller = controllers.firstOrNull() ?: continue
                    val metadata = controller.metadata ?: continue

                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: continue
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

                    val isNewTrack = (title != currentTitle || artist != currentArtist)

                    // 1. 画像を取得
                    var bitmap: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: getActiveNotificationArtwork(title)

                    if (bitmap == null) {
                        val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                        if (!uriStr.isNullOrEmpty()) {
                            bitmap = fetchBitmapFromUri(uriStr)
                        }
                    }

                    // 画像の簡易ハッシュ (画像が変わったかどうかを検知)
                    val bmpHash = bitmap?.generationId ?: 0

                    // 曲が変わった場合、または同じ曲の画像が遅れてロード完了した場合に送信
                    if (isNewTrack || (bmpHash != 0 && bmpHash != currentImageHash)) {
                        currentTitle = title
                        currentArtist = artist
                        currentImageHash = bmpHash

                        val finalBitmap = bitmap ?: createPlaceholderBitmap(240, 240)
                        val processed = processBitmapToSquare(finalBitmap, 240)
                        val jpegBytes = compressToJpeg(processed, quality = 65)

                        val displayBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        withContext(Dispatchers.Main) {
                            MediaStateHolder.updateTrack(title, artist, album, displayBmp, jpegBytes)
                        }

                        // ESP32へ安全送信
                        BluetoothSppManager.sendMediaPacket(title, artist, album, jpegBytes)
                        Log.d(TAG, "Dispatched Track: $title (Hash: $bmpHash)")
                    }
                } catch (e: Exception) {
                    // エラー時はスキップして次回チェック
                }
            }
        }
    }

    private fun getActiveNotificationArtwork(expectedTitle: String): Bitmap? {
        val notifications = activeNotifications ?: return null
        // 1. タイトルが一致する通知から取得
        for (sbn in notifications) {
            val notif = sbn.notification
            val extras = notif.extras
            val notifTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

            if (notifTitle.isNotEmpty() && (notifTitle.contains(expectedTitle, ignoreCase = true) || expectedTitle.contains(notifTitle, ignoreCase = true))) {
                val icon = notif.getLargeIcon()
                if (icon != null) {
                    return icon.loadDrawable(this)?.toBitmap()
                }
            }
        }
        // 2. メディア通知から取得
        for (sbn in notifications) {
            val notif = sbn.notification
            if (notif.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                val icon = notif.getLargeIcon()
                if (icon != null) {
                    return icon.loadDrawable(this)?.toBitmap()
                }
            }
        }
        return null
    }

    private suspend fun fetchBitmapFromUri(uriStr: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
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

    private fun processBitmapToSquare(source: Bitmap, targetSize: Int = 240): Bitmap {
        val minEdge = minOf(source.width, source.height)
        val cropX = (source.width - minEdge) / 2
        val cropY = (source.height - minEdge) / 2

        val cropped = Bitmap.createBitmap(source, cropX, cropY, minEdge, minEdge)
        return Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
    }

    private fun compressToJpeg(bitmap: Bitmap, quality: Int = 65): ByteArray {
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