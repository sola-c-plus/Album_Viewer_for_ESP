package com.example.album_viewer

import android.app.Notification
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaListenerService"
    }

    private var sessionManager: MediaSessionManager? = null
    private var monitorJob: Job? = null
    private var updateDebounceJob: Job? = null
    private var popupDismissJob: Job? = null
    private var artworkRetryJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isUpdating = AtomicBoolean(false)

    private val activeControllers = ConcurrentHashMap<MediaController, MediaController.Callback>()
    private val handledNotificationKeys = LruCache<String, Long>(50)

    // ★ アルバムアートのメモリキャッシュ（高速スキップ・再訪時の即時表示用）
    private val artworkCache = LruCache<String, Bitmap>(30)

    private var currentTitle = ""
    private var currentArtist = ""
    private var currentPackage = ""
    private var lastSentTrackSignature = ""
    private var isShowingNotificationPopup = false

    private var lastRawArtwork: Bitmap? = null

    private var stopDetectionTimestamp: Long = 0L
    private var isStandbyScreenActive = false
    private var lastSentMinute = -1
    private var lastSentBatteryPct = -1

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        registerControllerCallbacks(controllers)
        triggerUpdateWithDebounce(100)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, MediaListenerService::class.java)
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionListener, component)
            val initial = sessionManager?.getActiveSessions(component)
            registerControllerCallbacks(initial)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register active sessions listener", e)
        }

        // ESP32からの物理ボタン入力を受信
        BluetoothSppManager.onCommandReceived = { cmd ->
            handleMediaCommand(cmd)
        }

        serviceScope.launch {
            BluetoothSppManager.connectionStatus.collect { status ->
                if (status == ConnectionStatus.CONNECTED) {
                    delay(800)
                    isStandbyScreenActive = false
                    currentTitle = ""
                    currentArtist = ""
                    currentPackage = ""
                    lastSentTrackSignature = ""
                    lastSentMinute = -1
                    stopDetectionTimestamp = 0L
                    triggerUpdateDirect()
                }
            }
        }

        startMonitoringLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothSppManager.onCommandReceived = null
        monitorJob?.cancel()
        updateDebounceJob?.cancel()
        popupDismissJob?.cancel()
        artworkRetryJob?.cancel()
        unregisterAllCallbacks()
        sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
    }

    private fun handleMediaCommand(cmd: Byte) {
        serviceScope.launch {
            try {
                val component = ComponentName(this@MediaListenerService, MediaListenerService::class.java)
                val controllers = sessionManager?.getActiveSessions(component) ?: emptyList()

                val targetController = controllers.firstOrNull { isPlaybackStateActive(it.playbackState) }
                    ?: controllers.firstOrNull { it.packageName == currentPackage }
                    ?: controllers.firstOrNull()

                if (targetController != null) {
                    val controls = targetController.transportControls
                    when (cmd.toInt()) {
                        0x01 -> { // PLAY_PAUSE
                            val state = targetController.playbackState?.state
                            if (state == PlaybackState.STATE_PLAYING) {
                                controls.pause()
                            } else {
                                controls.play()
                            }
                        }
                        0x02 -> controls.skipToNext() // NEXT
                        0x03 -> controls.skipToPrevious() // PREV
                    }
                    Log.d(TAG, "Executed command 0x%02X on %s".format(cmd, targetController.packageName))
                } else {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val keyCode = when (cmd.toInt()) {
                        0x01 -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        0x02 -> KeyEvent.KEYCODE_MEDIA_NEXT
                        0x03 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        else -> 0
                    }
                    if (keyCode != 0 && audioManager != null) {
                        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                        Log.d(TAG, "Dispatched fallback KeyEvent: $keyCode")
                    }
                }
                triggerUpdateWithDebounce(50)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling media command: $cmd", e)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (shouldShowNotificationPopup(sbn)) {
            handleNotificationPopup(sbn)
            return
        }
        triggerUpdateWithDebounce(100)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        triggerUpdateWithDebounce(150)
    }

    private fun shouldShowNotificationPopup(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false

        val notif = sbn.notification ?: return false
        val extras = notif.extras ?: return false
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false

        val pkg = sbn.packageName.lowercase()
        val isTargetApp = pkg.contains("line") || pkg.contains("discord") ||
                          pkg.contains("dialer") || pkg.contains("telecom") ||
                          pkg.contains("messaging") || pkg.contains("mms") ||
                          pkg.contains("twitter") || pkg.contains("instagram") ||
                          pkg.contains("slack") || pkg.contains("whatsapp") ||
                          pkg.contains("gmail") || pkg.contains("mail")

        if (!isTargetApp) return false

        val title = extractTitle(null, extras)
        val text = extractArtist(null, extras)
        if (title.isBlank() && text.isBlank()) return false

        val notifKey = "${sbn.key}_${sbn.postTime}"
        if (handledNotificationKeys.get(notifKey) != null) return false
        handledNotificationKeys.put(notifKey, System.currentTimeMillis())
        return true
    }

    private fun handleNotificationPopup(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return
        val pkg = sbn.packageName

        val title = extractTitle(null, extras)
        val text = extractArtist(null, extras)
        if (title.isBlank() && text.isBlank()) return

        val appName = getAppDisplayName(pkg)
        val appIcon = getAppIconBitmap(pkg, notif)
        val accentColor = getAppAccentColor(pkg)

        serviceScope.launch {
            isShowingNotificationPopup = true
            popupDismissJob?.cancel()

            val popupBitmap = drawNotificationPopupOnBitmap(
                baseBitmap = lastRawArtwork,
                appName = appName,
                title = title.ifBlank { appName },
                text = text.ifBlank { "新しい通知があります" },
                appIcon = appIcon,
                accentColor = accentColor,
                targetSize = 240
            )

            val jpegBytes = compressToSafeBaselineJpeg(popupBitmap)
            val displayTitle = "🔔 $appName: $title"
            val displayArtist = text
            val rgb565Color = colorToRgb565(accentColor)
            val rgb565Bg = 0x0000

            withContext(Dispatchers.Main) {
                MediaStateHolder.updateTrack(displayTitle, displayArtist, "", pkg, popupBitmap, jpegBytes)
            }

            BluetoothSppManager.sendMediaPacket(displayTitle, displayArtist, "", jpegBytes, rgb565Color, rgb565Bg)

            popupDismissJob = launch {
                delay(5000)
                isShowingNotificationPopup = false
                isStandbyScreenActive = false
                stopDetectionTimestamp = 0L
                lastSentTrackSignature = ""
                triggerUpdateDirect()
            }
        }
    }

    private fun registerControllerCallbacks(controllers: List<MediaController>?) {
        unregisterAllCallbacks()
        if (controllers == null) return

        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    triggerUpdateWithDebounce(80)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    triggerUpdateWithDebounce(80)
                }
            }
            try {
                controller.registerCallback(callback)
                activeControllers[controller] = callback
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun unregisterAllCallbacks() {
        for ((controller, callback) in activeControllers) {
            try {
                controller.unregisterCallback(callback)
            } catch (e: Exception) {
                // ignore
            }
        }
        activeControllers.clear()
    }

    private fun startMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(800)
                if (!isShowingNotificationPopup) {
                    updateActiveMedia()
                }
            }
        }
    }

    private fun triggerUpdateWithDebounce(delayMs: Long) {
        if (isShowingNotificationPopup) return
        updateDebounceJob?.cancel()
        updateDebounceJob = serviceScope.launch {
            delay(delayMs)
            updateActiveMedia()
        }
    }

    private fun triggerUpdateDirect() {
        if (!isShowingNotificationPopup) {
            serviceScope.launch {
                updateActiveMedia()
            }
        }
    }

    private suspend fun updateActiveMedia() {
        if (isShowingNotificationPopup) return
        if (!isUpdating.compareAndSet(false, true)) return

        try {
            val trackInfo = detectCurrentActiveTrack()
            val isMusicActuallyPlaying = (trackInfo != null && trackInfo.title.isNotBlank() && trackInfo.isPlaying)

            if (isMusicActuallyPlaying && trackInfo != null) {
                stopDetectionTimestamp = 0L
                isStandbyScreenActive = false

                val hasArt = (trackInfo.bitmap != null)
                val artSignature = trackInfo.bitmap?.let { "${it.width}x${it.height}_${it.generationId}_${it.allocationByteCount}" } ?: "no_art"
                val trackSignature = "${trackInfo.packageName}_${trackInfo.title}_${trackInfo.artist}_${hasArt}_$artSignature"

                val isNewTrackOrArtUpdated = (trackSignature != lastSentTrackSignature)

                if (isNewTrackOrArtUpdated) {
                    lastSentTrackSignature = trackSignature
                    currentTitle = trackInfo.title
                    currentArtist = trackInfo.artist
                    currentPackage = trackInfo.packageName

                    lastRawArtwork = trackInfo.bitmap
                    val rawBitmap = trackInfo.bitmap ?: generatePlaceholderArtwork(trackInfo.title, 240)

                    val (rgb565Accent, rgb565Bg, fullBgColor) = extractOptimalPaletteWithFullColor(rawBitmap)

                    val squareBmp = processBitmapWithBottomFade(rawBitmap, fullBgColor, 240)
                    val jpegBytes = compressToSafeBaselineJpeg(squareBmp)

                    withContext(Dispatchers.Main) {
                        MediaStateHolder.updateTrack(trackInfo.title, trackInfo.artist, trackInfo.album, trackInfo.packageName, squareBmp, jpegBytes)
                    }

                    BluetoothSppManager.sendMediaPacket(trackInfo.title, trackInfo.artist, trackInfo.album, jpegBytes, rgb565Accent, rgb565Bg)
                    Log.d(TAG, "Sent Track Art: ${trackInfo.title} (hasArt=$hasArt)")

                    // ★ 画像がまだ届いていない場合は、音楽アプリのアート非同期取得を連続追跡
                    if (!hasArt) {
                        scheduleAdaptiveArtworkRetry(trackInfo.title, trackInfo.packageName)
                    }
                }
            } else {
                val now = System.currentTimeMillis()
                val cal = Calendar.getInstance()
                val currentMinute = cal.get(Calendar.MINUTE)
                val (batteryPct, isCharging) = getBatteryInfo()

                if (stopDetectionTimestamp == 0L) {
                    stopDetectionTimestamp = now
                } else if (!isStandbyScreenActive && (now - stopDetectionTimestamp >= 2500L)) {
                    isStandbyScreenActive = true
                    lastSentTrackSignature = ""
                    currentTitle = ""
                    currentArtist = ""
                    currentPackage = ""
                    lastSentMinute = currentMinute
                    lastSentBatteryPct = batteryPct

                    sendStandbyDashboard(cal, batteryPct, isCharging)
                } else if (isStandbyScreenActive) {
                    if (currentMinute != lastSentMinute || Math.abs(batteryPct - lastSentBatteryPct) >= 5) {
                        lastSentMinute = currentMinute
                        lastSentBatteryPct = batteryPct
                        sendStandbyDashboard(cal, batteryPct, isCharging)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateActiveMedia", e)
        } finally {
            isUpdating.set(false)
        }
    }

    /**
     * ★ 高速スキップ追跡：音楽アプリが後からアルバムアートを読み込むタイミングを段階的に追跡
     */
    private fun scheduleAdaptiveArtworkRetry(targetTitle: String, targetPackage: String) {
        artworkRetryJob?.cancel()
        artworkRetryJob = serviceScope.launch {
            val delays = longArrayOf(100, 200, 350, 500, 800, 1200, 1800)
            for (d in delays) {
                delay(d)
                if (!isActive) break
                // 既に別の曲にスキップされていたら即座に中断
                if (currentTitle != targetTitle) break

                val track = detectCurrentActiveTrack()
                if (track != null && track.title == targetTitle && track.bitmap != null) {
                    // 本物のアートが届いた！即時更新
                    triggerUpdateDirect()
                    break
                }
            }
        }
    }

    private suspend fun sendStandbyDashboard(cal: Calendar, batteryPct: Int, isCharging: Boolean) {
        val dashboardBmp = drawStandbyDashboardBitmap(cal, batteryPct, isCharging, 240)
        val jpegBytes = compressToSafeBaselineJpeg(dashboardBmp)

        val title = "📱 SMART STANDBY"
        val artist = if (isCharging) "⚡ Charging $batteryPct% | Ready" else "🔋 Battery $batteryPct% | Ready"
        val defaultCyanRgb565 = colorToRgb565(Color.rgb(0, 210, 255))
        val blackBgRgb565 = 0x0000

        withContext(Dispatchers.Main) {
            MediaStateHolder.updateTrack(title, artist, "", "System Standby", dashboardBmp, jpegBytes)
        }

        BluetoothSppManager.sendMediaPacket(title, artist, "", jpegBytes, defaultCyanRgb565, blackBgRgb565)
    }

    private data class PaletteResult(val accentRgb565: Int, val bgRgb565: Int, val fullBgColor: Int)

    private fun extractOptimalPaletteWithFullColor(bitmap: Bitmap): PaletteResult {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 24, 24, false)
            val pixels = IntArray(24 * 24)
            scaled.getPixels(pixels, 0, 24, 0, 0, 24, 24)

            var bestColor = Color.rgb(0, 210, 255)
            var maxScore = -1f
            val hsv = FloatArray(3)

            for (pixel in pixels) {
                Color.colorToHSV(pixel, hsv)
                val s = hsv[1]
                val v = hsv[2]

                if (v < 0.25f || (s < 0.15f && v > 0.85f)) continue

                val score = s * 0.7f + v * 0.3f
                if (score > maxScore) {
                    maxScore = score
                    bestColor = pixel
                }
            }

            Color.colorToHSV(bestColor, hsv)
            if (hsv[2] < 0.85f) hsv[2] = 0.85f
            if (hsv[1] < 0.45f) hsv[1] = 0.45f
            val accentRgb565 = colorToRgb565(Color.HSVToColor(hsv))

            hsv[1] = (hsv[1] * 0.8f).coerceIn(0.35f, 0.65f)
            hsv[2] = 0.12f
            val fullBgColor = Color.HSVToColor(hsv)
            val bgRgb565 = colorToRgb565(fullBgColor)

            PaletteResult(accentRgb565, bgRgb565, fullBgColor)
        } catch (e: Exception) {
            val defCyan = Color.rgb(0, 210, 255)
            PaletteResult(colorToRgb565(defCyan), 0x0000, Color.BLACK)
        }
    }

    private fun colorToRgb565(colorInt: Int): Int {
        val r = (Color.red(colorInt) shr 3) and 0x1F
        val g = (Color.green(colorInt) shr 2) and 0x3F
        val b = (Color.blue(colorInt) shr 3) and 0x1F
        return (r shl 11) or (g shl 5) or b
    }

    private fun cropSmartFocusSquare(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val aspectRatio = width.toFloat() / height.toFloat()

        if (aspectRatio < 1.15f) {
            val minEdge = minOf(width, height)
            val cropX = (width - minEdge) / 2
            val cropY = (height - minEdge) / 2
            return Bitmap.createBitmap(source, cropX, cropY, minEdge, minEdge)
        }

        val sampleW = 64
        val sampleH = 36
        val sampleBmp = Bitmap.createScaledBitmap(source, sampleW, sampleH, false)
        val pixels = IntArray(sampleW * sampleH)
        sampleBmp.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)

        val hsv = FloatArray(3)
        val colEnergy = FloatArray(sampleW)

        for (x in 0 until sampleW) {
            var energySum = 0f
            val normX = x.toFloat() / sampleW
            val isExtremeEdgeX = normX < 0.06f || normX > 0.94f

            for (y in 0 until sampleH) {
                val idx = y * sampleW + x
                val c = pixels[idx]
                Color.colorToHSV(c, hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]

                val isWarmOrSkin = (h in 5f..50f || h in 320f..360f) && (s in 0.12f..0.75f) && (v in 0.35f..0.98f)
                val skinBonus = if (isWarmOrSkin) 2.4f else 1.0f

                val isTextOrUi = (s < 0.10f && (v > 0.88f || v < 0.15f))
                val textPenalty = if (isTextOrUi) 0.25f else 1.0f

                var edge = 0f
                if (x < sampleW - 1) {
                    val rightC = pixels[idx + 1]
                    val rDiff = Math.abs(Color.red(c) - Color.red(rightC))
                    val gDiff = Math.abs(Color.green(c) - Color.green(rightC))
                    val bDiff = Math.abs(Color.blue(c) - Color.blue(rightC))
                    edge = ((rDiff + gDiff + bDiff) / 765f).coerceAtMost(0.6f)
                }

                val yWeight = if (y > sampleH * 0.7f) 0.4f else 1.0f
                var pEnergy = (edge * 0.35f + s * 0.45f + v * 0.2f) * skinBonus * textPenalty * yWeight
                if (isExtremeEdgeX) pEnergy *= 0.2f

                energySum += pEnergy
            }

            val distCenter = Math.abs(normX - 0.5f)
            val compBias = 1.0f - distCenter * 0.35f
            colEnergy[x] = energySum * compBias
        }

        val cropSize = (height * 0.88f).toInt()
        val windowW = (sampleH * 0.88f).toInt()

        var maxEnergy = -1f
        var bestStartX = 0

        for (x in 0..(sampleW - windowW)) {
            var currentWindowEnergy = 0f
            for (w in 0 until windowW) {
                currentWindowEnergy += colEnergy[x + w]
            }
            if (currentWindowEnergy > maxEnergy) {
                maxEnergy = currentWindowEnergy
                bestStartX = x
            }
        }

        val scale = width.toFloat() / sampleW.toFloat()
        var actualCropX = (bestStartX * scale).toInt()
        val maxCropX = width - cropSize
        if (actualCropX < 0) actualCropX = 0
        if (actualCropX > maxCropX) actualCropX = maxCropX

        val actualCropY = 0
        return Bitmap.createBitmap(source, actualCropX, actualCropY, cropSize, cropSize)
    }

    private fun processBitmapWithBottomFade(source: Bitmap, fadeToColor: Int, targetSize: Int = 240): Bitmap {
        val focusedSquare = cropSmartFocusSquare(source)
        val scaled = Bitmap.createScaledBitmap(focusedSquare, targetSize, targetSize, true)

        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        canvas.drawColor(fadeToColor)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaled, 0f, 0f, paint)

        val fadeStart = 165f
        val fadeEnd = 194f
        val gradientShader = LinearGradient(
            0f, fadeStart, 0f, fadeEnd,
            Color.TRANSPARENT,
            fadeToColor,
            Shader.TileMode.CLAMP
        )
        val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = gradientShader
        }
        canvas.drawRect(0f, fadeStart, targetSize.toFloat(), fadeEnd, gradPaint)

        val bottomSolidPaint = Paint().apply {
            color = fadeToColor
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, fadeEnd, targetSize.toFloat(), targetSize.toFloat(), bottomSolidPaint)

        return result
    }

    data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String,
        val bitmap: Bitmap?,
        val packageName: String,
        val isPlaying: Boolean,
        val lastUpdateTime: Long
    )

    private fun detectCurrentActiveTrack(): TrackInfo? {
        val candidates = mutableListOf<TrackInfo>()
        val notifications = activeNotifications ?: emptyArray()

        try {
            val component = ComponentName(this, MediaListenerService::class.java)
            val controllers = sessionManager?.getActiveSessions(component) ?: emptyList()

            for (c in controllers) {
                val metadata = c.metadata ?: continue
                val state = c.playbackState
                val pkg = c.packageName ?: "unknown"

                val matchingSbn = findBestMediaNotification(notifications, pkg)
                val notifPlayingState = matchingSbn?.let { isNotificationShowingPauseButton(it.notification) }

                val isPlaying = when {
                    isPlaybackStateActive(state) -> true
                    state?.state == PlaybackState.STATE_PAUSED || state?.state == PlaybackState.STATE_STOPPED -> false
                    notifPlayingState != null -> notifPlayingState
                    else -> false
                }

                val updateTime = state?.lastPositionUpdateTime ?: matchingSbn?.postTime ?: 0L
                val title = extractTitle(metadata, matchingSbn?.notification?.extras)
                val artist = extractArtist(metadata, matchingSbn?.notification?.extras)
                val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

                if (title.isNotBlank()) {
                    val cacheKey = "${pkg}_${title}_${artist}"
                    var bitmap = artworkCache.get(cacheKey)

                    if (bitmap == null) {
                        // 1. MediaMetadataから直接取得
                        bitmap = extractArtworkFromMetadata(metadata)

                        // 2. 通知から取得（★超重要：通知の曲名が現在の曲名と一致している場合のみ採用！）
                        if (bitmap == null && matchingSbn != null) {
                            val sbnTitle = extractTitle(null, matchingSbn.notification?.extras)
                            if (isTitleMatch(title, sbnTitle)) {
                                bitmap = extractArtworkFromNotification(matchingSbn.notification?.extras, matchingSbn.notification)
                            }
                        }

                        // 3. ローカルMediaStore または 高解像度通知フォールバック
                        if (bitmap == null) {
                            bitmap = findLocalArtworkByMediaStore(title, artist)
                                ?: getNotificationHighResArtFallback(title)
                        }

                        if (bitmap != null) {
                            artworkCache.put(cacheKey, bitmap)
                        }
                    }

                    candidates.add(TrackInfo(title, artist, album, bitmap, pkg, isPlaying, updateTime))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaSession search error", e)
        }

        for (sbn in notifications) {
            val notif = sbn.notification ?: continue
            val extras = notif.extras ?: continue
            val pkg = sbn.packageName.lowercase()

            val isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                          notif.category == Notification.CATEGORY_TRANSPORT ||
                          hasMediaActionButtons(notif) ||
                          pkg.contains("spotify") || pkg.contains("youtube") || pkg.contains("music") ||
                          pkg.contains("audio") || pkg.contains("sound") || pkg.contains("radiko") ||
                          pkg.contains("podcast") || pkg.contains("player")

            if (isMedia) {
                val title = extractTitle(null, extras)
                val artist = extractArtist(null, extras)
                val album = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
                val postTime = sbn.postTime
                val isPlaying = isNotificationShowingPauseButton(notif) ?: true

                if (title.isNotBlank()) {
                    if (candidates.none { it.packageName.equals(pkg, ignoreCase = true) }) {
                        val cacheKey = "${pkg}_${title}_${artist}"
                        var bitmap = artworkCache.get(cacheKey)
                        if (bitmap == null) {
                            bitmap = extractArtworkFromNotification(extras, notif)
                                ?: findLocalArtworkByMediaStore(title, artist)
                            if (bitmap != null) {
                                artworkCache.put(cacheKey, bitmap)
                            }
                        }
                        candidates.add(TrackInfo(title, artist, album, bitmap, pkg, isPlaying, postTime))
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null

        return candidates
            .sortedWith(compareByDescending<TrackInfo> { it.isPlaying }
                .thenByDescending { it.lastUpdateTime })
            .firstOrNull()
    }

    /**
     * 曲名が一致しているかを判定（通知の遅延による前の曲のアートワーク誤爆を防ぐ）
     */
    private fun isTitleMatch(currentTitle: String, notifTitle: String): Boolean {
        if (currentTitle.isBlank() || notifTitle.isBlank()) return false
        val t1 = currentTitle.trim().lowercase()
        val t2 = notifTitle.trim().lowercase()
        return t1 == t2 || t1.contains(t2) || t2.contains(t1)
    }

    private fun findBestMediaNotification(notifications: Array<StatusBarNotification>, packageName: String): StatusBarNotification? {
        val appNotifs = notifications.filter { it.packageName.equals(packageName, ignoreCase = true) }
        if (appNotifs.isEmpty()) return null
        return appNotifs.firstOrNull { 
            it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            it.notification.category == Notification.CATEGORY_TRANSPORT ||
            it.isOngoing
        } ?: appNotifs.firstOrNull()
    }

    private fun isPlaybackStateActive(state: PlaybackState?): Boolean {
        if (state == null) return false
        return when (state.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING -> {
                val speed = state.playbackSpeed
                speed > 0f || speed == -1f
            }
            else -> false
        }
    }

    private fun isNotificationShowingPauseButton(notif: Notification?): Boolean? {
        if (notif == null) return null
        val actions = notif.actions ?: return null

        val pauseRegex = Regex("""^(pause|stop|一時停止|停止)$""", RegexOption.IGNORE_CASE)
        val playRegex = Regex("""^(play|resume|再生|再開)$""", RegexOption.IGNORE_CASE)

        for (action in actions) {
            val title = action.title?.toString()?.trim() ?: ""
            if (title.isNotEmpty()) {
                if (pauseRegex.containsMatchIn(title)) return true
                if (playRegex.containsMatchIn(title)) return false
            }
            val iconStr = action.getIcon()?.toString()?.lowercase() ?: ""
            if (iconStr.contains("pause")) return true
            if (iconStr.contains("play") && !iconStr.contains("playlist")) return false
        }
        return null
    }

    private fun hasMediaActionButtons(notif: Notification?): Boolean {
        val actions = notif?.actions ?: return false
        for (action in actions) {
            val title = action.title?.toString()?.lowercase() ?: ""
            val icon = action.getIcon()?.toString()?.lowercase() ?: ""
            if (title.contains("pause") || title.contains("play") || title.contains("再生") ||
                title.contains("next") || title.contains("prev") || icon.contains("media")) {
                return true
            }
        }
        return false
    }

    private fun findLocalArtworkByMediaStore(title: String, artist: String): Bitmap? {
        if (title.isBlank()) return null
        return try {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST
            )
            val selection = "${MediaStore.Audio.Media.TITLE} LIKE ?"
            val selectionArgs = arrayOf("%$title%")

            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val foundArtist = if (artistCol != -1) cursor.getString(artistCol) else ""

                    if (artist.isNotBlank() && foundArtist.isNotBlank() &&
                        !foundArtist.contains(artist, ignoreCase = true) &&
                        !artist.contains(foundArtist, ignoreCase = true)
                    ) {
                        continue
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val itemUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                            val thumb = contentResolver.loadThumbnail(itemUri, Size(240, 240), null)
                            if (thumb != null) return thumb
                        } catch (e: Exception) {
                            // ignore
                        }
                    }

                    if (albumId > 0) {
                        try {
                            val albumArtUri = ContentUris.withAppendedId(
                                Uri.parse("content://media/external/audio/albumart"),
                                albumId
                            )
                            contentResolver.openInputStream(albumArtUri)?.use { inputStream ->
                                val bmp = BitmapFactory.decodeStream(inputStream)
                                if (bmp != null) return bmp
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTitle(metadata: MediaMetadata?, extras: Bundle?): String {
        metadata?.let {
            it.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { s -> s.isNotBlank() }?.let { return it }
            it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { s -> s.isNotBlank() }?.let { return it }
        }
        extras?.let {
            it.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { s -> s.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun extractArtist(metadata: MediaMetadata?, extras: Bundle?): String {
        metadata?.let {
            it.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { s -> s.isNotBlank() }?.let { return it }
            it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { s -> s.isNotBlank() }?.let { return it }
            it.getString(MediaMetadata.METADATA_KEY_AUTHOR)?.takeIf { s -> s.isNotBlank() }?.let { return it }
            it.getString(MediaMetadata.METADATA_KEY_COMPOSER)?.takeIf { s -> s.isNotBlank() }?.let { return it }
            it.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.takeIf { s -> s.isNotBlank() }?.let { return it }
        }
        extras?.let {
            it.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { s -> s.isNotBlank() }?.let { return it }
            it.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { s -> s.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun extractArtworkFromMetadata(metadata: MediaMetadata): Bitmap? {
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }

        val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: metadata.description?.iconUri?.toString()

        if (!uriStr.isNullOrEmpty()) {
            fetchBitmapFromUri(uriStr)?.let { return it }
        }

        metadata.description?.iconBitmap?.let { return it }
        metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }
        return null
    }

    private fun extractArtworkFromNotification(extras: Bundle?, notif: Notification?): Bitmap? {
        if (extras != null) {
            if (extras.containsKey(Notification.EXTRA_PICTURE)) {
                val pic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    extras.getParcelable<Parcelable>(Notification.EXTRA_PICTURE) as? Bitmap
                }
                if (pic != null) return pic
            }

            if (extras.containsKey(Notification.EXTRA_LARGE_ICON_BIG)) {
                val bigIcon = extras.get(Notification.EXTRA_LARGE_ICON_BIG)
                when (bigIcon) {
                    is Bitmap -> return bigIcon
                    is Icon -> {
                        try {
                            bigIcon.loadDrawable(this)?.toBitmap()?.let { return it }
                        } catch (e: Exception) {}
                    }
                }
            }

            if (extras.containsKey(Notification.EXTRA_LARGE_ICON)) {
                val largeIcon = extras.get(Notification.EXTRA_LARGE_ICON)
                when (largeIcon) {
                    is Bitmap -> return largeIcon
                    is Icon -> {
                        try {
                            largeIcon.loadDrawable(this)?.toBitmap()?.let { return it }
                        } catch (e: Exception) {}
                    }
                }
            }
        }

        notif?.getLargeIcon()?.let { icon ->
            try {
                icon.loadDrawable(this)?.toBitmap()?.let { return it }
            } catch (e: Exception) {}
        }

        @Suppress("DEPRECATION")
        notif?.largeIcon?.let { return it }

        return null
    }

    private fun getNotificationHighResArtFallback(expectedTitle: String): Bitmap? {
        val notifications = activeNotifications ?: return null
        for (sbn in notifications) {
            val notif = sbn.notification ?: continue
            val extras = notif.extras ?: continue
            val title = extractTitle(null, extras)
            if (isTitleMatch(expectedTitle, title)) {
                extractArtworkFromNotification(extras, notif)?.let { return it }
            }
        }
        return null
    }

    private fun fetchBitmapFromUri(uriStr: String): Bitmap? {
        if (uriStr.isBlank()) return null
        return try {
            val uri = Uri.parse(uriStr)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    val url = URL(uriStr)
                    val conn = url.openConnection() as HttpURLConnection
                    // 高速スキップ対応のためタイムアウトを1.2秒に短縮
                    conn.connectTimeout = 1200
                    conn.readTimeout = 1200
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.instanceFollowRedirects = true
                    conn.doInput = true
                    conn.connect()
                    conn.inputStream.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                }
                "content", "file", "android.resource" -> {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                }
                else -> {
                    if (uriStr.startsWith("/")) {
                        BitmapFactory.decodeFile(uriStr)
                    } else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun drawStandbyDashboardBitmap(
        cal: Calendar,
        batteryPct: Int,
        isCharging: Boolean,
        size: Int = 240
    ): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.rgb(15, 18, 26))

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 210, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawCircle(120f, 120f, 112f, ringPaint)

        val dateStr = SimpleDateFormat("MM/dd EEE", Locale.US).format(cal.time).uppercase()
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(140, 165, 195)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(dateStr, 120f, 50f, datePaint)

        val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(cal.time)
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(timeStr, 120f, 102f, timePaint)

        val battStr = if (isCharging) "⚡ $batteryPct%" else "🔋 $batteryPct%"
        val battColor = when {
            isCharging -> Color.rgb(52, 199, 89)
            batteryPct <= 20 -> Color.rgb(255, 69, 58)
            else -> Color.rgb(0, 210, 255)
        }
        val battPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = battColor
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(battStr, 120f, 136f, battPaint)

        return bmp
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = registerReceiver(null, ifilter)
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        return Pair(batteryPct, isCharging)
    }

    private fun drawNotificationPopupOnBitmap(
        baseBitmap: Bitmap?,
        appName: String,
        title: String,
        text: String,
        appIcon: Bitmap?,
        accentColor: Int,
        targetSize: Int = 240
    ): Bitmap {
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)

        if (baseBitmap != null) {
            val minEdge = minOf(baseBitmap.width, baseBitmap.height)
            val cropX = (baseBitmap.width - minEdge) / 2
            val cropY = (baseBitmap.height - minEdge) / 2
            val cropped = Bitmap.createBitmap(baseBitmap, cropX, cropY, minEdge, minEdge)
            val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            canvas.drawColor(Color.argb(160, 0, 0, 0))
        } else {
            canvas.drawColor(Color.rgb(18, 20, 28))
        }

        val cardRect = RectF(16f, 22f, 224f, 158f)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 25, 27, 36)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRoundRect(cardRect, 18f, 18f, cardPaint)
        canvas.drawRoundRect(cardRect, 18f, 18f, borderPaint)

        val headerY = 46f
        var textStartX = 30f
        if (appIcon != null) {
            val iconScaled = Bitmap.createScaledBitmap(appIcon, 20, 20, true)
            canvas.drawBitmap(iconScaled, 28f, headerY - 15f, null)
            textStartX = 54f
        }

        val appNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(appName, textStartX, headerY, appNamePaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        val safeTitle = ellipsizeText(title, titlePaint, 175f)
        canvas.drawText(safeTitle, 28f, 76f, titlePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(215, 220, 235)
            textSize = 12f
            typeface = Typeface.DEFAULT
        }
        val safeText1 = ellipsizeText(text, textPaint, 175f)
        canvas.drawText(safeText1, 28f, 102f, textPaint)

        val remaining = text.drop(safeText1.length).trim()
        if (remaining.isNotEmpty()) {
            val safeText2 = ellipsizeText(remaining, textPaint, 175f)
            canvas.drawText(safeText2, 28f, 122f, textPaint)
        }

        return result
    }

    private fun ellipsizeText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var len = text.length
        while (len > 0 && paint.measureText(text.take(len) + "...") > maxWidth) {
            len--
        }
        return text.take(len) + "..."
    }

    private fun getAppDisplayName(packageName: String): String {
        return when {
            packageName.contains("line") -> "LINE"
            packageName.contains("discord") -> "Discord"
            packageName.contains("twitter") || packageName.contains("x") -> "X"
            packageName.contains("instagram") -> "Instagram"
            packageName.contains("dialer") || packageName.contains("telecom") -> "着信"
            packageName.contains("gm") || packageName.contains("mail") -> "Gmail"
            packageName.contains("slack") -> "Slack"
            packageName.contains("messaging") || packageName.contains("mms") -> "メッセージ"
            packageName.contains("whatsapp") -> "WhatsApp"
            else -> "通知"
        }
    }

    private fun getAppAccentColor(packageName: String): Int {
        return when {
            packageName.contains("line") -> Color.rgb(6, 199, 85)
            packageName.contains("discord") -> Color.rgb(88, 101, 242)
            packageName.contains("twitter") || packageName.contains("x") -> Color.rgb(29, 161, 242)
            packageName.contains("instagram") -> Color.rgb(225, 48, 108)
            packageName.contains("dialer") || packageName.contains("telecom") -> Color.rgb(52, 199, 89)
            packageName.contains("gm") || packageName.contains("mail") -> Color.rgb(234, 67, 53)
            packageName.contains("slack") -> Color.rgb(74, 21, 75)
            packageName.contains("whatsapp") -> Color.rgb(37, 211, 102)
            else -> Color.rgb(0, 210, 255)
        }
    }

    private fun getAppIconBitmap(packageName: String, notif: Notification): Bitmap? {
        return try {
            notif.getSmallIcon()?.loadDrawable(this)?.toBitmap()
                ?: packageManager.getApplicationIcon(packageName).toBitmap()
        } catch (e: Exception) {
            null
        }
    }

    private fun generatePlaceholderArtwork(title: String, size: Int = 240): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(25, 30, 45))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 210, 255)
            textSize = 72f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val initial = if (title.isNotBlank()) title.take(1).uppercase() else "♪"
        val bounds = Rect()
        paint.getTextBounds(initial, 0, initial.length, bounds)
        val yPos = (size / 2) + (bounds.height() / 2)

        canvas.drawText(initial, size / 2f, yPos.toFloat(), paint)
        return bmp
    }

    private fun compressToSafeBaselineJpeg(bitmap: Bitmap): ByteArray {
        var quality = 65
        var stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        var bytes = stream.toByteArray()

        while (bytes.size > 20480 && quality > 25) {
            quality -= 10
            stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            bytes = stream.toByteArray()
        }
        return bytes
    }
}