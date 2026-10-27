package com.example.album_viewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class MediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaListenerService"
        private const val CHANNEL_ID = "album_viewer_fg_channel"
        private const val NOTIF_ID = 9901
    }

    private var sessionManager: MediaSessionManager? = null
    private var monitorJob: Job? = null
    private var updateDebounceJob: Job? = null
    private var popupDismissJob: Job? = null
    private var artworkRetryJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isUpdating = AtomicBoolean(false)

    private val activeControllers = ConcurrentHashMap<MediaController, MediaController.Callback>()
    private val handledNotificationKeys = LruCache<String, Long>(300)
    // 容量は件数ではなくバイト基準（上限12MB）。実寸 Bitmap を多数保持して
    // 低メモリ時のプロセス kill を誘発しないよう、sizeOf で byteCount を数える。
    private val artworkCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private var currentTitle = ""
    private var currentArtist = ""
    private var currentPackage = ""
    private var lastSentTrackSignature = ""
    // プロトコル v2 では署名は「転送成功時」にコミットする (設計書 §5.3)。
    // 送信中の内容の署名をここに保持し、重複送信を防ぎつつ、失敗したら
    // コミットされないまま残ることで次の監視ティックで自然に再送させる。
    private var pendingTrackSignature = ""
    private var pendingStandbySignature = ""
    private var isShowingNotificationPopup = false
    private var isFirstListenerConnection = true
    private val isSweepingNotifications = AtomicBoolean(false)

    private var lastRawArtwork: Bitmap? = null

    // 現在 ESP に表示されている画像（曲アート送信時の squareBmp、またはスタンバイ
    // ダッシュボード）。ポップアップの背景にはこちらを使い、時計表示中に前の曲の
    // ジャケットが背景になるのを防ぐ。保持は 240×240 を1枚だけ参照する形にする。
    @Volatile
    private var lastDisplayedBitmap: Bitmap? = null

    private var stopDetectionTimestamp: Long = 0L
    private var isStandbyScreenActive = false
    private var lastSentMinute = -1
    private var lastSentBatteryPct = -1
    private var lastSentStandbySignature = ""

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        registerControllerCallbacks(controllers)
        triggerUpdateWithDebounce(200)
    }

    override fun onCreate() {
        super.onCreate()
        BluetoothSppManager.init(applicationContext)
        startForegroundNotification()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ESP32 Music Display Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ESP32とのBluetooth接続をスリープ中も維持します"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ESP32 Display 接続待機中")
            .setContentText("音楽再生と通知をESP32に中継しています")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            Log.d(TAG, "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }
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
                    pendingTrackSignature = ""
                    lastSentMinute = -1
                    lastSentStandbySignature = ""
                    pendingStandbySignature = ""
                    stopDetectionTimestamp = 0L
                    triggerUpdateDirect()
                }
            }
        }

        if (isFirstListenerConnection) {
            // 初回接続時は既存の通知を一斉にポップアップさせないよう、処理済み登録だけ行う
            isFirstListenerConnection = false
            primeExistingNotifications()
        } else {
            // 再接続時は、未接続の隙間にポストされた未処理の通知を拾う
            sweepMissedNotifications()
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
                        0x01 -> {
                            val state = targetController.playbackState?.state
                            if (state == PlaybackState.STATE_PLAYING) {
                                controls.pause()
                            } else {
                                controls.play()
                            }
                        }
                        0x02 -> controls.skipToNext()
                        0x03 -> controls.skipToPrevious()
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
                triggerUpdateWithDebounce(100)
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
        triggerUpdateWithDebounce(200)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        triggerUpdateWithDebounce(200)
    }

    /**
     * 通知シェード全体を走査し、onNotificationPosted で取りこぼした通知を拾う。
     * 対象判定と重複防止は shouldShowNotificationPopup / handledNotificationKeys をそのまま再利用する。
     * リスナー未接続の隙間や再接続直後にポストされた通知が対象。
     */
    private fun sweepMissedNotifications() {
        if (isShowingNotificationPopup) return
        if (!isSweepingNotifications.compareAndSet(false, true)) return
        try {
            val notifications = activeNotifications ?: return
            for (sbn in notifications) {
                if (isShowingNotificationPopup) break
                // スイープでは15分以上前の通知は対象外にする。
                // 古い通知が重複防止キャッシュから追い出された後に「新規」と判定されて
                // 再ポップアップするのを防ぎ、無駄な走査も抑える。
                // onNotificationPosted 経由の通常ポップアップにはこの時間制限は付けない。
                if (System.currentTimeMillis() - sbn.postTime > 15 * 60 * 1000L) continue
                if (shouldShowNotificationPopup(sbn)) {
                    Log.d(TAG, "Sweep picked up missed notification: ${sbn.packageName}")
                    handleNotificationPopup(sbn)
                    // 1回の走査で起動するポップアップは1件まで。
                    // 残りは次回ティックのスイープで順に拾う。
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in sweepMissedNotifications", e)
        } finally {
            isSweepingNotifications.set(false)
        }
    }

    /**
     * 初回接続時に、既に通知シェードにある通知を「処理済み」として登録だけする。
     * shouldShowNotificationPopup は true を返す対象について handledNotificationKeys へ
     * キーを登録するため、戻り値を捨てて呼び出すことで古い通知の一斉ポップアップを防ぐ。
     */
    private fun primeExistingNotifications() {
        try {
            val notifications = activeNotifications ?: return
            for (sbn in notifications) {
                shouldShowNotificationPopup(sbn)
            }
            Log.d(TAG, "Primed ${notifications.size} existing notifications on first connection")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prime existing notifications", e)
        }
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
                          pkg.contains("gmail") || pkg.contains("mail") ||
                          pkg.contains("facebook.aura")

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

        // 表示中フラグはコルーチン起動「前」に同期的に立てる。
        // コルーチン内で非同期に立てると、スイープ等の連続呼び出しが
        // フラグのチェックをすり抜け、複数ポップアップが同時発火する。
        isShowingNotificationPopup = true
        // ポップアップの表示窓 (10秒) の起点。転送リトライの可否判定に使う。
        val popupStartMs = System.currentTimeMillis()

        serviceScope.launch {
            try {
                popupDismissJob?.cancel()

                val popupBitmap = drawNotificationPopupOnBitmap(
                    // 背景は「現在表示中の画像」を優先する（スタンバイ中なら時計画面）。
                    // まだ何も表示していない場合だけ最後の曲アートにフォールバックする。
                    baseBitmap = lastDisplayedBitmap ?: lastRawArtwork,
                    appName = appName,
                    title = title.ifBlank { appName },
                    text = text.ifBlank { "新しい通知があります" },
                    appIcon = appIcon,
                    accentColor = accentColor,
                    targetSize = 240
                )

                // ポップアップ画像は写真背景で大きくなりやすく、大きな転送が ESP に
                // 届き切らない実績があるため、届く実績のあるサイズ (8KB) を目標にする。
                val jpegBytes = compressToSafeBaselineJpeg(popupBitmap, targetBytes = 8192)
                Log.d(TAG, "Popup image: ${jpegBytes?.size ?: -1} bytes")
                val displayTitle = "🔔 $appName: $title"
                val displayArtist = text
                val rgb565Color = colorToRgb565(accentColor)
                val rgb565Bg = 0x0000

                withContext(Dispatchers.Main) {
                    MediaStateHolder.updateTrack(displayTitle, displayArtist, "", pkg, popupBitmap, jpegBytes)
                }

                // 表示窓 (10秒) は転送の成否に依らず進めるため、先に解除ジョブを起動する
                popupDismissJob = launch {
                    delay(10000)
                    isShowingNotificationPopup = false
                    isStandbyScreenActive = false
                    stopDetectionTimestamp = 0L
                    lastSentTrackSignature = ""
                    lastSentStandbySignature = ""
                    pendingTrackSignature = ""
                    pendingStandbySignature = ""
                    triggerUpdateDirect()
                }

                // ポップアップは送信中フレームを切らないよう、キャンセルしない順番待ち送信を使う。
                // プロトコル v2 では転送結果が返るため、失敗した場合は表示窓の範囲内で
                // 最大2回まで全体リトライする (設計書 §5.3 / §8 D9)。ポップアップは
                // イベント性コンテンツで「次の更新で自然に再送」が効かないため。
                var attempt = 0
                var delivered = false
                while (!delivered && attempt < 3) {
                    if (attempt > 0) {
                        // リトライ前の鮮度確認: まだ表示中か、表示窓内か、接続が生きているか
                        if (!isShowingNotificationPopup) break
                        if (System.currentTimeMillis() - popupStartMs >= 10_000L) break
                        if (BluetoothSppManager.connectionStatus.value != ConnectionStatus.CONNECTED) break
                        Log.d(TAG, "Retrying popup transfer: attempt=${attempt + 1}")
                    }
                    delivered = sendPopupOnce(displayTitle, displayArtist, jpegBytes, rgb565Color, rgb565Bg)
                    attempt++
                }
                if (!delivered) {
                    Log.w(TAG, "Popup transfer failed after $attempt attempt(s)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 描画・圧縮・送信のどこかで例外が出ても、表示中フラグが張り付いて
                // 以後の表示更新（曲・スタンバイ・ポップアップ）がサービス再起動まで
                // 全停止しないよう、状態をリセットして通常更新へ復帰させる。
                Log.e(TAG, "Error in notification popup handling", e)
                isShowingNotificationPopup = false
                isStandbyScreenActive = false
                stopDetectionTimestamp = 0L
                lastSentTrackSignature = ""
                lastSentStandbySignature = ""
                pendingTrackSignature = ""
                pendingStandbySignature = ""
                triggerUpdateDirect()
            }
        }
    }

    // ポップアップ送信1回分を、転送結果が返るまで待って真偽で返す。
    // BluetoothSppManager の結果コールバックを suspend で受けるための薄いラッパー。
    private suspend fun sendPopupOnce(
        title: String,
        artist: String,
        jpegBytes: ByteArray?,
        rgb565Color: Int,
        rgb565Bg: Int
    ): Boolean = suspendCancellableCoroutine { cont ->
        BluetoothSppManager.sendMediaPacketQueued(title, artist, "", jpegBytes, rgb565Color, rgb565Bg) { success ->
            if (cont.isActive) cont.resume(success)
        }
    }

    private fun registerControllerCallbacks(controllers: List<MediaController>?) {
        unregisterAllCallbacks()
        if (controllers == null) return

        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    triggerUpdateWithDebounce(150)
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    triggerUpdateWithDebounce(150)
                }
            }
            try {
                controller.registerCallback(callback)
                activeControllers[controller] = callback
            } catch (e: Exception) {}
        }
    }

    private fun unregisterAllCallbacks() {
        for ((controller, callback) in activeControllers) {
            try {
                controller.unregisterCallback(callback)
            } catch (e: Exception) {}
        }
        activeControllers.clear()
    }

    private fun startMonitoringLoop() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                if (!isShowingNotificationPopup) {
                    updateActiveMedia()
                    sweepMissedNotifications()
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
                lastSentStandbySignature = ""
                pendingStandbySignature = ""

                val hasArt = (trackInfo.bitmap != null)
                val artSignature = trackInfo.bitmap?.let { "${it.width}x${it.height}_${it.generationId}_${it.allocationByteCount}" } ?: "no_art"
                val trackSignature = "${trackInfo.packageName}_${trackInfo.title}_${trackInfo.artist}_${hasArt}_$artSignature"

                // 送信中 (pending) の内容も重複送信しない。コミット済みでも送信中でも
                // ない内容だけが新規として送られる。
                val isNewTrackOrArtUpdated = (trackSignature != lastSentTrackSignature &&
                    trackSignature != pendingTrackSignature)

                if (isNewTrackOrArtUpdated) {
                    pendingTrackSignature = trackSignature
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

                    lastDisplayedBitmap = squareBmp
                    // 署名は転送成功時にコミットする (設計書 §5.3)。失敗した場合は
                    // コミットされないため、次の監視ティックで同じ内容が再送される。
                    BluetoothSppManager.sendMediaPacket(trackInfo.title, trackInfo.artist, trackInfo.album, jpegBytes, rgb565Accent, rgb565Bg) { success ->
                        val stillPending = (pendingTrackSignature == trackSignature)
                        if (stillPending) pendingTrackSignature = ""
                        if (success && stillPending) {
                            lastSentTrackSignature = trackSignature
                            Log.d(TAG, "Track transfer confirmed: ${trackInfo.title}")
                        } else if (!success) {
                            Log.w(TAG, "Track transfer failed, will retry on next update: ${trackInfo.title}")
                        }
                    }
                    Log.d(TAG, "Sent Track Art: ${trackInfo.title} (hasArt=$hasArt)")

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
                    pendingTrackSignature = ""
                    currentTitle = ""
                    currentArtist = ""
                    currentPackage = ""
                    // lastSentMinute / lastSentBatteryPct は sendStandbyDashboard 側で
                    // 転送成功時にコミットする (ここで先に進めると、転送失敗時に
                    // 分・電池の変化条件が消えて自己回復できなくなる)。

                    sendStandbyDashboard(cal, batteryPct, isCharging)
                } else if (isStandbyScreenActive) {
                    if (currentMinute != lastSentMinute || Math.abs(batteryPct - lastSentBatteryPct) >= 5) {
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

    private fun scheduleAdaptiveArtworkRetry(targetTitle: String, targetPackage: String) {
        artworkRetryJob?.cancel()
        artworkRetryJob = serviceScope.launch {
            val delays = longArrayOf(200, 400, 700, 1200)
            for (d in delays) {
                delay(d)
                if (!isActive) break
                if (currentTitle != targetTitle) break

                val track = detectCurrentActiveTrack()
                if (track != null && track.title == targetTitle && track.bitmap != null) {
                    triggerUpdateDirect()
                    break
                }
            }
        }
    }

    private suspend fun sendStandbyDashboard(cal: Calendar, batteryPct: Int, isCharging: Boolean) {
        // 同一内容（分・電池%・充電状態）の二重送信を防ぐ署名ガード（唯一の送信チョークポイント）。
        // 接続リセットやポップアップ解除による再遷移と、分切り替わりの再送分岐が重なっても、
        // 内容が同じなら実際に送るのは1回だけにする。
        // 署名は「復帰送信が前提のリセット経路」（ポップアップ解除・再接続・再生開始）で
        // クリアする。クリア後の復帰送信は1回だけ通って署名が再記録されるため、
        // 競合する2回目は引き続き抑止できる。逆にクリアしないと、復帰送信自体が
        // 同一内容として抑止され、ポップアップ画像や曲アートが次の分境界まで
        // ESP に残ってしまう。
        // プロトコル v2 では、コミット済み署名 (lastSentStandbySignature) に加えて
        // 送信中署名 (pendingStandbySignature) でも抑止し、コミットは転送成功時に
        // 行う。クリア対象には pending 側も含める（でないと送信中だった内容の
        // コールバックが後からコミットしてしまい、復帰送信が抑止される）。
        val signature = "${cal.get(Calendar.DAY_OF_YEAR)}_${cal.get(Calendar.HOUR_OF_DAY)}_" +
            "${cal.get(Calendar.MINUTE)}_${batteryPct}_$isCharging"
        if (signature == lastSentStandbySignature || signature == pendingStandbySignature) {
            Log.d(TAG, "Skip duplicate standby dashboard: $signature")
            return
        }
        pendingStandbySignature = signature
        val sentMinute = cal.get(Calendar.MINUTE)

        val dashboardBmp = drawStandbyDashboardBitmap(cal, batteryPct, isCharging, 240)
        val jpegBytes = compressToSafeBaselineJpeg(dashboardBmp)

        val title = "📱 SMART STANDBY"
        val artist = if (isCharging) "⚡ Charging $batteryPct% | Ready" else "🔋 Battery $batteryPct% | Ready"
        val defaultCyanRgb565 = colorToRgb565(Color.rgb(0, 210, 255))
        val blackBgRgb565 = 0x0000

        withContext(Dispatchers.Main) {
            MediaStateHolder.updateTrack(title, artist, "", "System Standby", dashboardBmp, jpegBytes)
        }

        lastDisplayedBitmap = dashboardBmp
        // 署名と分・電池の記録は転送成功時にコミットする (設計書 §5.3)。
        // 失敗したら何も記録されないため、次の監視ティックで再送条件が再び立つ。
        BluetoothSppManager.sendMediaPacket(title, artist, "", jpegBytes, defaultCyanRgb565, blackBgRgb565) { success ->
            val stillPending = (pendingStandbySignature == signature)
            if (stillPending) pendingStandbySignature = ""
            if (success && stillPending) {
                lastSentStandbySignature = signature
                lastSentMinute = sentMinute
                lastSentBatteryPct = batteryPct
            } else if (!success) {
                Log.w(TAG, "Standby transfer failed, will retry on next update: $signature")
            }
        }
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

    private fun processBitmapWithBottomFade(source: Bitmap, fadeToColor: Int, targetSize: Int = 240): Bitmap {
        val minEdge = minOf(source.width, source.height)
        val cropX = (source.width - minEdge) / 2
        val cropY = (source.height - minEdge) / 2

        val cropped = Bitmap.createBitmap(source, cropX, cropY, minEdge, minEdge)
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)

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

    /**
     * キャッシュ保持用に、長辺512px超の Bitmap は512px以下へ縮小したコピーを返す。
     * 表示は 240×240 のため画質への影響はない。元の Bitmap 自体は変更しない
     * （lastRawArtwork 等は元の参照のまま使う）。
     */
    private fun downscaleForArtworkCache(bitmap: Bitmap): Bitmap {
        val maxEdge = maxOf(bitmap.width, bitmap.height)
        if (maxEdge <= 512) return bitmap
        val scale = 512f / maxEdge
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

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
                        bitmap = extractArtworkFromMetadata(metadata)

                        if (bitmap == null && matchingSbn != null) {
                            val sbnTitle = extractTitle(null, matchingSbn.notification?.extras)
                            if (isTitleMatch(title, sbnTitle)) {
                                bitmap = extractArtworkFromNotification(matchingSbn.notification?.extras, matchingSbn.notification)
                            }
                        }

                        if (bitmap == null) {
                            bitmap = findLocalArtworkByMediaStore(title, artist)
                                ?: getNotificationHighResArtFallback(title)
                        }

                        if (bitmap != null) {
                            artworkCache.put(cacheKey, downscaleForArtworkCache(bitmap))
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
                                artworkCache.put(cacheKey, downscaleForArtworkCache(bitmap))
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
                        } catch (e: Exception) {}
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
                        } catch (e: Exception) {}
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

        // 円形ディスプレイ (GC9A01) に合わせ、カード自体もディスプレイと同心の
        // 円形 (中心 120,120・半径 108) にする。テキストは各行の y における円の
        // 弦幅に収まる範囲へ配置する。
        // 中身は下部に ESP のドック表示が重なるため、全体を 14px 上寄せにしている。
        val center = targetSize / 2f
        val cardRadius = 108f
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 25, 27, 36)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawCircle(center, center, cardRadius, cardPaint)
        canvas.drawCircle(center, center, cardRadius, borderPaint)

        val headerY = 50f
        var textStartX = 56f
        if (appIcon != null) {
            val iconScaled = Bitmap.createScaledBitmap(appIcon, 20, 20, true)
            canvas.drawBitmap(iconScaled, 56f, headerY - 15f, null)
            textStartX = 82f
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
        val titleMaxWidth = 176f
        val safeTitle = ellipsizeText(title, titlePaint, titleMaxWidth)
        canvas.drawText(safeTitle, center - titleMaxWidth / 2f, 80f, titlePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(215, 220, 235)
            textSize = 12f
            typeface = Typeface.DEFAULT
        }
        // 本文は最大4行まで折り返す。各行の最大幅は、その行の帯における
        // 円 (半径108) の弦幅から余白 16px を引いたものにする。
        val bodyLineYs = floatArrayOf(104f, 124f, 144f, 164f)
        val bodyLineWidths = bodyLineYs.map { y ->
            circleLineMaxWidth(center, cardRadius, y, textPaint.textSize)
        }
        val bodyLines = wrapTextIntoLines(text, textPaint, bodyLineWidths)
        bodyLines.forEachIndexed { index, line ->
            val lineWidth = bodyLineWidths[index]
            canvas.drawText(line, center - lineWidth / 2f, bodyLineYs[index], textPaint)
        }

        // 円形ディスプレイ (GC9A01, 240×240) に合わせ、中心円 (半径120) の外側を
        // 黒で落とす。RGB_565 にはアルファが無いため、円の外側を塗りつぶす方式にする。
        val outsideCirclePath = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, targetSize.toFloat(), targetSize.toFloat(), Path.Direction.CW)
            addCircle(center, center, center, Path.Direction.CW)
        }
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.drawPath(outsideCirclePath, maskPaint)

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

    // 円 (中心 center, 半径 radius) の内側で、baseline のテキスト行が使える最大幅。
    // 行の帯 (baseline - textSize .. baseline) のうち円中心から最も遠い端の y で
    // 弦幅を求め、余白として 16px を引く。
    private fun circleLineMaxWidth(center: Float, radius: Float, baseline: Float, textSize: Float): Float {
        val dy = maxOf(abs(baseline - textSize - center), abs(baseline - center))
        if (dy >= radius) return 0f
        val halfChord = sqrt(radius * radius - dy * dy)
        return 2f * halfChord - 16f
    }

    // テキストを lineWidths の各行幅で折り返し、最大 lineWidths.size 行まで返す。
    // 最終行までに収まらない分は最終行を省略記号で切り詰める。
    private fun wrapTextIntoLines(text: String, paint: Paint, lineWidths: List<Float>): List<String> {
        val normalized = text.replace('\n', ' ').replace('\r', ' ')
        val lines = mutableListOf<String>()
        var rest = normalized
        for ((index, maxWidth) in lineWidths.withIndex()) {
            if (rest.isEmpty()) break
            if (paint.measureText(rest) <= maxWidth) {
                lines.add(rest)
                rest = ""
                break
            }
            if (index == lineWidths.lastIndex) {
                lines.add(ellipsizeText(rest, paint, maxWidth))
                rest = ""
                break
            }
            var count = paint.breakText(rest, 0, rest.length, true, maxWidth, null)
            // サロゲートペア (絵文字等) の途中で折らないよう1文字戻す
            if (count in 1 until rest.length &&
                Character.isHighSurrogate(rest[count - 1]) && Character.isLowSurrogate(rest[count])
            ) {
                count--
            }
            if (count <= 0) break
            lines.add(rest.substring(0, count))
            rest = rest.substring(count).trimStart()
        }
        return lines
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
            packageName.contains("facebook.aura") -> "Muse"
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
            packageName.contains("facebook.aura") -> Color.rgb(139, 92, 246)
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

    private fun compressToSafeBaselineJpeg(bitmap: Bitmap, targetBytes: Int = 20480): ByteArray? {
        var quality = 65
        var stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        var bytes = stream.toByteArray()

        while (bytes.size > targetBytes && quality > 10) {
            quality = maxOf(10, quality - 10)
            stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            bytes = stream.toByteArray()
        }
        // 品質10まで下げても目標サイズを超える場合は画像を送らない。
        // 大きすぎる画像は ESP 側で受信が完走しない/読み捨てられるため、
        // null を返してメタデータのみ送信する形にする。
        if (bytes.size > targetBytes) return null
        return bytes
    }
}