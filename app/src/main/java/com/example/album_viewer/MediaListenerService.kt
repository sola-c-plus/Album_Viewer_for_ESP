package com.example.album_viewer

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
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
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache
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
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isUpdating = AtomicBoolean(false)

    private val activeControllers = ConcurrentHashMap<MediaController, MediaController.Callback>()
    private val handledNotificationKeys = LruCache<String, Long>(50)

    private var currentTitle = ""
    private var currentArtist = ""
    private var currentPackage = ""
    private var lastSentTrackSignature = ""
    private var isShowingNotificationPopup = false

    private var lastRawArtwork: Bitmap? = null

    // 停止後1.5秒判定用
    private var stopDetectionTimestamp: Long = 0L
    private var isStandbyScreenActive = false
    private var lastSentMinute = -1
    private var lastSentBatteryPct = -1

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        registerControllerCallbacks(controllers)
        triggerUpdateWithDebounce(300)
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

        serviceScope.launch {
            BluetoothSppManager.connectionStatus.collect { status ->
                if (status == ConnectionStatus.CONNECTED) {
                    Log.d(TAG, "Bluetooth Connected -> Resyncing...")
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
        monitorJob?.cancel()
        updateDebounceJob?.cancel()
        popupDismissJob?.cancel()
        unregisterAllCallbacks()
        sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        if (shouldShowNotificationPopup(sbn)) {
            handleNotificationPopup(sbn)
            return
        }

        triggerUpdateWithDebounce(300)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        triggerUpdateWithDebounce(300)
    }

    private fun shouldShowNotificationPopup(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false

        val notif = sbn.notification ?: return false
        val extras = notif.extras ?: return false

        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false

        val pkg = sbn.packageName.lowercase()

        val isTargetApp = pkg.contains("line") ||
                          pkg.contains("discord") ||
                          pkg.contains("dialer") ||
                          pkg.contains("telecom") ||
                          pkg.contains("messaging") ||
                          pkg.contains("mms") ||
                          pkg.contains("twitter") ||
                          pkg.contains("instagram") ||
                          pkg.contains("slack") ||
                          pkg.contains("whatsapp") ||
                          pkg.contains("gmail") ||
                          pkg.contains("mail")

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

            val jpegBytes = compressToBaselineJpeg(popupBitmap, quality = 65)
            val displayTitle = "🔔 $appName: $title"
            val displayArtist = text

            withContext(Dispatchers.Main) {
                MediaStateHolder.updateTrack(displayTitle, displayArtist, "", pkg, popupBitmap, jpegBytes)
            }

            BluetoothSppManager.sendMediaPacket(displayTitle, displayArtist, "", jpegBytes)
            Log.d(TAG, "Notification Popup Sent: [$appName] $title")

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
                    triggerUpdateWithDebounce(450)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    triggerUpdateWithDebounce(200)
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
                delay(600)
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

            // ★真の再生中判定：曲が存在し、かつ明示的に再生中（isPlaying == true）
            val isMusicActuallyPlaying = (trackInfo != null && trackInfo.title.isNotBlank() && trackInfo.isPlaying)

            if (isMusicActuallyPlaying && trackInfo != null) {
                // ==========================================
                // 🎵 1. 音楽再生中（PLAYING）
                // ==========================================
                stopDetectionTimestamp = 0L
                isStandbyScreenActive = false

                val trackSignature = "${trackInfo.packageName}_${trackInfo.title}_${trackInfo.artist}"
                val isNewTrack = (trackSignature != lastSentTrackSignature)

                if (isNewTrack) {
                    lastSentTrackSignature = trackSignature
                    currentTitle = trackInfo.title
                    currentArtist = trackInfo.artist
                    currentPackage = trackInfo.packageName

                    lastRawArtwork = trackInfo.bitmap
                    val rawBitmap = trackInfo.bitmap ?: generatePlaceholderArtwork(trackInfo.title, 240)
                    val squareBmp = processBitmapToStandardBaseline(rawBitmap, 240)
                    val jpegBytes = compressToBaselineJpeg(squareBmp, quality = 65)

                    withContext(Dispatchers.Main) {
                        MediaStateHolder.updateTrack(trackInfo.title, trackInfo.artist, trackInfo.album, trackInfo.packageName, squareBmp, jpegBytes)
                    }

                    BluetoothSppManager.sendMediaPacket(trackInfo.title, trackInfo.artist, trackInfo.album, jpegBytes)
                    Log.d(TAG, "Sent Track & Art: [${trackInfo.packageName}] ${trackInfo.title} (${jpegBytes.size} bytes)")
                }
            } else {
                // ==========================================
                // ⏸️ 2. 音楽停止中（一時停止 PAUSED または 完全停止）
                // ==========================================
                val now = System.currentTimeMillis()
                val cal = Calendar.getInstance()
                val currentMinute = cal.get(Calendar.MINUTE)
                val (batteryPct, isCharging) = getBatteryInfo()

                if (stopDetectionTimestamp == 0L) {
                    stopDetectionTimestamp = now
                } else if (!isStandbyScreenActive && (now - stopDetectionTimestamp >= 1500L)) {
                    // ★一時停止から1.5秒経過！時計（スマートダッシュボード）へ即時切り替え
                    isStandbyScreenActive = true
                    lastSentTrackSignature = "" // 再生再開時に即座にアルバムアートを再送できるようにリセット
                    currentTitle = ""
                    currentArtist = ""
                    currentPackage = ""
                    lastSentMinute = currentMinute
                    lastSentBatteryPct = batteryPct

                    sendStandbyDashboard(cal, batteryPct, isCharging)
                    Log.d(TAG, "Music Stopped/Paused -> Switched to Standby Clock")
                } else if (isStandbyScreenActive) {
                    // 待機画面中：1分ごとに時計画像を更新（時計が止まらない）
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

    private suspend fun sendStandbyDashboard(cal: Calendar, batteryPct: Int, isCharging: Boolean) {
        val dashboardBmp = drawStandbyDashboardBitmap(cal, batteryPct, isCharging, 240)
        val jpegBytes = compressToBaselineJpeg(dashboardBmp, quality = 65)

        val title = "📱 SMART STANDBY"
        val artist = if (isCharging) "⚡ Charging $batteryPct% | Ready" else "🔋 Battery $batteryPct% | Ready"

        withContext(Dispatchers.Main) {
            MediaStateHolder.updateTrack(title, artist, "", "System Standby", dashboardBmp, jpegBytes)
        }

        BluetoothSppManager.sendMediaPacket(title, artist, "", jpegBytes)
        Log.d(TAG, "Standby Clock Updated: ${cal.get(Calendar.HOUR_OF_DAY)}:${cal.get(Calendar.MINUTE)}")
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

    /**
     * 各アプリのMediaSessionおよび通知ボタン(Play/Pause)を精密解析して再生判定
     */
    private fun detectCurrentActiveTrack(): TrackInfo? {
        val candidates = mutableListOf<TrackInfo>()
        val notifications = activeNotifications

        // 1. MediaSessionコントローラの探索
        try {
            val component = ComponentName(this, MediaListenerService::class.java)
            val controllers = sessionManager?.getActiveSessions(component) ?: emptyList()

            for (c in controllers) {
                val metadata = c.metadata ?: continue
                val state = c.playbackState
                val pkg = c.packageName ?: "unknown"

                // 該当アプリの通知を探す
                val matchingSbn = notifications?.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
                val notifPlayingState = matchingSbn?.let { isNotificationPlaying(it.notification) }

                // ★再生判定の精密決定：
                // ① 通知にPlay/Pauseボタンがあればそれが最優先
                // ② なければ PlaybackState の state と playbackSpeed で判定
                val isPlaying = if (notifPlayingState != null) {
                    notifPlayingState
                } else {
                    val isStatePlay = (state?.state == PlaybackState.STATE_PLAYING || 
                                       state?.state == PlaybackState.STATE_BUFFERING)
                    val isSpeedNormal = (state?.playbackSpeed ?: 1.0f) > 0f
                    isStatePlay && isSpeedNormal
                }

                val updateTime = state?.lastPositionUpdateTime ?: matchingSbn?.postTime ?: 0L
                val title = extractTitle(metadata, matchingSbn?.notification?.extras)
                val artist = extractArtist(metadata, matchingSbn?.notification?.extras)
                val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

                if (title.isNotBlank()) {
                    val bitmap = extractArtworkFromMetadata(metadata)
                        ?: matchingSbn?.let { extractArtworkFromNotification(it.notification.extras, it.notification) }
                        ?: getNotificationHighResArtFallback(title)

                    candidates.add(TrackInfo(title, artist, album, bitmap, pkg, isPlaying, updateTime))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaSession search error", e)
        }

        // 2. 通知バーのメディア通知の探索 (MediaSessionを持たないアプリの補完)
        if (notifications != null) {
            for (sbn in notifications) {
                val notif = sbn.notification ?: continue
                val extras = notif.extras ?: continue
                val isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
                val pkg = sbn.packageName.lowercase()

                if (isMedia || pkg.contains("spotify") || pkg.contains("youtube") ||
                    pkg.contains("music") || pkg.contains("audio") || pkg.contains("sound") || pkg.contains("radiko")) {

                    val title = extractTitle(null, extras)
                    val artist = extractArtist(null, extras)
                    val album = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
                    val postTime = sbn.postTime

                    // 通知の再生ボタン解析
                    val isPlaying = isNotificationPlaying(notif) ?: true

                    if (title.isNotBlank()) {
                        if (candidates.none { it.packageName.equals(pkg, ignoreCase = true) }) {
                            val bitmap = extractArtworkFromNotification(extras, notif)
                            candidates.add(TrackInfo(title, artist, album, bitmap, pkg, isPlaying, postTime))
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null

        // 再生中(isPlaying == true)を最優先でソート
        return candidates
            .sortedWith(compareByDescending<TrackInfo> { it.isPlaying }
                .thenByDescending { it.lastUpdateTime })
            .firstOrNull()
    }

    /**
     * ★通知のアクションボタンを解析して「再生中」か「一時停止中」かを100%特定
     */
    private fun isNotificationPlaying(notif: Notification?): Boolean? {
        if (notif == null) return null
        val actions = notif.actions ?: return null
        for (action in actions) {
            val title = action.title?.toString()?.lowercase() ?: ""
            // 「再生」「Play」ボタンが表示されている ＝ 今は一時停止中！
            if (title.contains("play") || title.contains("再生") || title.contains("resume") || title.contains("再開")) {
                return false
            }
            // 「一時停止」「Pause」ボタンが表示されている ＝ 今は再生中！
            if (title.contains("pause") || title.contains("一時停止") || title.contains("stop") || title.contains("停止")) {
                return true
            }
        }
        return null
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
        if (!uriStr.isNullOrEmpty()) {
            fetchBitmapFromUri(uriStr)?.let { return it }
        }

        metadata.description?.iconBitmap?.let { return it }
        metadata.description?.iconUri?.toString()?.let { uri ->
            fetchBitmapFromUri(uri)?.let { return it }
        }

        metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }
        return null
    }

    private fun extractArtworkFromNotification(extras: Bundle, notif: Notification): Bitmap? {
        if (extras.containsKey(Notification.EXTRA_PICTURE)) {
            val pic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable<Parcelable>(Notification.EXTRA_PICTURE) as? Bitmap
            }
            if (pic != null) return pic
        }

        notif.getLargeIcon()?.let { icon ->
            icon.loadDrawable(this)?.toBitmap()?.let { return it }
        }

        return null
    }

    private fun getNotificationHighResArtFallback(expectedTitle: String): Bitmap? {
        val notifications = activeNotifications ?: return null
        for (sbn in notifications) {
            val notif = sbn.notification ?: continue
            val extras = notif.extras ?: continue
            val title = extractTitle(null, extras)
            if (title.contains(expectedTitle, ignoreCase = true) || expectedTitle.contains(title, ignoreCase = true)) {
                extractArtworkFromNotification(extras, notif)?.let { return it }
            }
        }
        return null
    }

    private fun fetchBitmapFromUri(uriStr: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "http" || uri.scheme == "https") {
                val url = URL(uriStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.instanceFollowRedirects = true
                conn.doInput = true
                conn.connect()
                conn.inputStream.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } else {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
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

    private fun compressToBaselineJpeg(bitmap: Bitmap, quality: Int = 65): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}