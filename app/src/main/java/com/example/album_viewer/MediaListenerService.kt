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
    private var pollingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var lastSentTitle = ""
    private var lastSentArtist = ""

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        startMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
    }

    private fun startMonitoring() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                delay(400) // 400msごとに定期チェック

                try {
                    val component = ComponentName(this@MediaListenerService, MediaListenerService::class.java)
                    val controllers = sessionManager?.getActiveSessions(component) ?: emptyList()
                    val controller = controllers.firstOrNull() ?: continue
                    val metadata = controller.metadata ?: continue

                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: continue
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

                    // 曲が変わった時だけ処理
                    if (title != lastSentTitle || artist != lastSentArtist) {
                        Log.d(TAG, "Track changed: $title")
                        
                        // プレイヤーの画像読み込み完了を0.4秒待機 (ズレ防止)
                        delay(400)

                        var bitmap: Bitmap? = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                            ?: getActiveNotificationArtwork()

                        if (bitmap == null) {
                            val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                            if (!uriStr.isNullOrEmpty()) {
                                bitmap = fetchBitmapFromUri(uriStr)
                            }
                        }

                        val finalBitmap = bitmap ?: createPlaceholderBitmap(240, 240)
                        // ★240x240等倍フルサイズで送信 (ズレを完全解消)
                        val processed = processBitmapToSquare(finalBitmap, 240)
                        val jpegBytes = compressToJpeg(processed, quality = 65)

                        lastSentTitle = title
                        lastSentArtist = artist

                        val displayBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        withContext(Dispatchers.Main) {
                            MediaStateHolder.updateTrack(title, artist, album, displayBmp, jpegBytes)
                        }

                        BluetoothSppManager.sendMediaPacket(title, artist, album, jpegBytes)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Monitor Error", e)
                }
            }
        }
    }

    private fun getActiveNotificationArtwork(): Bitmap? {
        val notifications = activeNotifications ?: return null
        for (sbn in notifications) {
            val notif = sbn.notification
            if (notif.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                return notif.getLargeIcon()?.loadDrawable(this)?.toBitmap()
            }
        }
        for (sbn in notifications) {
            val pkg = sbn.packageName.lowercase()
            if (pkg.contains("youtube") || pkg.contains("spotify") || pkg.contains("music")) {
                return sbn.notification.getLargeIcon()?.loadDrawable(this)?.toBitmap()
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