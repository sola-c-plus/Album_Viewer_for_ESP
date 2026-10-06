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
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val activeControllers = mutableListOf<MediaController>()
    private var lastTrackKey = ""
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveSessions(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataChange(metadata)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(this, MediaListenerService::class.java)

        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
            val initialControllers = sessionManager?.getActiveSessions(componentName)
            updateActiveSessions(initialControllers)
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification access permission not granted", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        unregisterControllers()
    }

    private fun updateActiveSessions(controllers: List<MediaController>?) {
        unregisterControllers()
        if (controllers.isNullOrEmpty()) return

        activeControllers.addAll(controllers)
        for (controller in activeControllers) {
            controller.registerCallback(controllerCallback)
        }

        val currentController = activeControllers.firstOrNull()
        handleMetadataChange(currentController?.metadata)
    }

    private fun unregisterControllers() {
        for (controller in activeControllers) {
            controller.unregisterCallback(controllerCallback)
        }
        activeControllers.clear()
    }

    private fun handleMetadataChange(metadata: MediaMetadata?) {
        if (metadata == null) return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown Artist"
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        val trackKey = "$title|$artist"
        if (trackKey == lastTrackKey) {
            return
        }
        lastTrackKey = trackKey

        serviceScope.launch {
            // ★重要: 通知のサムネイル画像が新曲に置き換わるまで少し待機 (前曲画像の誤取得を防止)
            delay(350)

            // 1. メタデータから直接Bitmap
            var bitmap: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

            // 2. 通知から取得 (曲名が一致する通知を最大3回リトライして確実に新曲画像を取得)
            if (bitmap == null) {
                for (retry in 0..2) {
                    bitmap = getArtworkFromNotification(title)
                    if (bitmap != null) break
                    delay(150)
                }
            }

            // 3. Web URLからダウンロード
            if (bitmap == null) {
                val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                if (!uriStr.isNullOrEmpty()) {
                    bitmap = fetchBitmapFromUri(uriStr)
                }
            }

            val finalBitmap = bitmap ?: createPlaceholderBitmap(240, 240)
            val processedBitmap = processBitmapTo240x240(finalBitmap)
            val jpegBytes = compressToJpeg(processedBitmap, quality = 72)

            withContext(Dispatchers.Main) {
                MediaStateHolder.updateTrack(title, artist, album, processedBitmap, jpegBytes)
            }
            BluetoothSppManager.sendMediaPacket(title, artist, album, jpegBytes)
        }
    }

    /**
     * 曲名が合致する通知から画像(LargeIcon)を取得
     */
    private fun getArtworkFromNotification(targetTitle: String): Bitmap? {
        return try {
            val notifications = activeNotifications ?: return null
            for (sbn in notifications) {
                val extras = sbn.notification.extras
                val notifTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                
                // タイトルが一致または含まれる通知を優先
                if (targetTitle.isNotEmpty() && (notifTitle.contains(targetTitle) || targetTitle.contains(notifTitle))) {
                    val icon = sbn.notification.getLargeIcon()
                    if (icon != null) {
                        return icon.loadDrawable(this)?.toBitmap()
                    }
                }
            }
            
            // 見つからなければアクティブな先頭の通知から取得
            for (sbn in notifications) {
                val icon = sbn.notification.getLargeIcon()
                if (icon != null) {
                    return icon.loadDrawable(this)?.toBitmap()
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchBitmapFromUri(uriStr: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                val url = URL(uriStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
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

    private fun processBitmapTo240x240(source: Bitmap, targetSize: Int = 240): Bitmap {
        val minEdge = minOf(source.width, source.height)
        val cropX = (source.width - minEdge) / 2
        val cropY = (source.height - minEdge) / 2

        val cropped = Bitmap.createBitmap(source, cropX, cropY, minEdge, minEdge)
        return Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
    }

    private fun compressToJpeg(bitmap: Bitmap, quality: Int = 72): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun createPlaceholderBitmap(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)
        val paint = Paint().apply {
            color = Color.LTGRAY
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("No Artwork", width / 2f, height / 2f + 10f, paint)
        return bmp
    }
}