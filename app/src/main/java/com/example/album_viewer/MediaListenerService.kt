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
import android.media.session.PlaybackState
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
    private var lastSentSignature = ""
    private var updateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveSessions(controllers)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            triggerMediaUpdate()
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            triggerMediaUpdate()
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        val isMedia = sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val pkg = sbn.packageName.lowercase()
        if (isMedia || pkg.contains("youtube") || pkg.contains("spotify") || pkg.contains("music")) {
            triggerMediaUpdate()
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

        triggerMediaUpdate()
    }

    private fun unregisterControllers() {
        for (controller in activeControllers) {
            controller.unregisterCallback(controllerCallback)
        }
        activeControllers.clear()
    }

    private fun getPlayingController(): MediaController? {
        return activeControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: activeControllers.firstOrNull()
    }

    private fun triggerMediaUpdate() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            delay(200)

            val controller = getPlayingController() ?: return@launch
            val metadata = controller.metadata ?: return@launch

            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return@launch
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

            var bitmap: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

            if (bitmap == null) {
                bitmap = getArtworkFromActiveMediaNotification()
            }

            if (bitmap == null) {
                val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                if (!uriStr.isNullOrEmpty()) {
                    bitmap = fetchBitmapFromUri(uriStr)
                }
            }

            if (bitmap == null) {
                delay(200)
                bitmap = getArtworkFromActiveMediaNotification()
            }

            val finalBitmap = bitmap ?: createPlaceholderBitmap(240, 240)
            val processedBitmap = processBitmapTo240x240(finalBitmap)
            
            // ★色・データ圧縮: 品質45% (約5〜8KBに極小化！)
            val jpegBytes = compressToJpeg(processedBitmap, quality = 45)

            val signature = "$title|$artist|${jpegBytes.size}"
            if (signature == lastSentSignature) {
                return@launch
            }
            lastSentSignature = signature

            withContext(Dispatchers.Main) {
                MediaStateHolder.updateTrack(title, artist, album, processedBitmap, jpegBytes)
            }
            BluetoothSppManager.sendMediaPacket(title, artist, album, jpegBytes)
        }
    }

    private fun getArtworkFromActiveMediaNotification(): Bitmap? {
        return try {
            val notifications = activeNotifications ?: return null
            for (sbn in notifications) {
                val notif = sbn.notification
                if (notif.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                    val icon = notif.getLargeIcon()
                    if (icon != null) {
                        return icon.loadDrawable(this)?.toBitmap()
                    }
                }
            }
            for (sbn in notifications) {
                val pkg = sbn.packageName.lowercase()
                if (pkg.contains("youtube") || pkg.contains("spotify") || pkg.contains("music")) {
                    val icon = sbn.notification.getLargeIcon()
                    if (icon != null) {
                        return icon.loadDrawable(this)?.toBitmap()
                    }
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

    private fun compressToJpeg(bitmap: Bitmap, quality: Int = 45): ByteArray {
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