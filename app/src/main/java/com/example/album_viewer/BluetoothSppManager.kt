package com.example.album_viewer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.UUID

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

object BluetoothSppManager {
    private const val TAG = "BluetoothSppManager"
    // ACK 待ちタイムアウト。起点はスマホ側の書き込み完了だが、ESP は受信を終えるまで
    // ACK を返せない。実測では ESP の受信完了まで最大約5.5秒かかった例があり、それに
    // 描画時間を加えても収まるよう 10 秒に設定する (旧 3 秒では受信途中に発火し、
    // タイムアウト再送が本物の NG 到着前に再送予算を空費しうる)。
    private const val IMAGE_ACK_TIMEOUT_MS = 10_000L
    // ACK 未確認のまま保持する送信レコードの上限 (FIFO。あふれた最古は回復対象から外す)
    private const val MAX_PENDING_IMAGE_SENDS = 4
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var receiveJob: Job? = null
    private var reconnectJob: Job? = null
    private var activeSendJob: Job? = null
    private val queuedSendJobs = Collections.synchronizedList(mutableListOf<Job>())

    // ★ 画像 ACK＋再送プロトコル (type 0x05)
    //
    // ESP は受信した画像を FIFO 順に1フレームずつ処理し、処理したフレームについて
    // ACK を返す。つまり ACK は常に「最も古い未確認フレーム」への報告であり、
    // 後続フレームの ACK が先に届くことはない。そのため送信レコードは最新1件では
    // なく、未確認のものを古い順にキュー保持する (旧方式は最新1件のみ保持だった
    // ため、通知バースト時に失敗フレームのレコードが即座に上書きされて「最新で
    // ない」ものになり、ESP が NG を返しても再送に結びつかなかった)。
    private class ImageSendRecord(
        val seq: Long,
        val title: String,
        val artist: String,
        val album: String,
        val jpegBytes: ByteArray,
        val rgb565Color: Int,
        val rgb565BgColor: Int,
        val checksum: Int
    ) {
        var acked: Boolean = false
        // 再送予算は起因別に分離する。タイムアウト再送は「ESP がまだ受信中かも
        // しれない」段階での仮回復なので、その後本物の NG が来たときにも再送
        // できるよう、NG 再送とは別枠にする (各最大1回)。
        var timeoutResendUsed: Boolean = false
        var ngResendUsed: Boolean = false
        var watchdogJob: Job? = null
        var resendJob: Job? = null
    }

    private enum class ResendReason { ACK_TIMEOUT, ACK_NG }

    // 未確認の送信レコードを古い順 (送信順 = ESP の処理順) に保持する FIFO キュー
    private val pendingImageSends = mutableListOf<ImageSendRecord>()
    private val pendingImageLock = Any()
    private var imageSendSeq = 0L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val sendMutex = Mutex()
    private val connectMutex = Mutex()

    private var targetDevice: BluetoothDevice? = null
    private var isAutoReconnectActive = true
    private var wakeLock: PowerManager.WakeLock? = null
    private var appContext: Context? = null

    var onCommandReceived: ((Byte) -> Unit)? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            val pm = appContext!!.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlbumViewer:BluetoothWakeLock")
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
                Log.d(TAG, "WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        targetDevice = device
        isAutoReconnectActive = true
        reconnectJob?.cancel()

        scope.launch {
            connectMutex.withLock {
                internalConnect(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun internalConnect(device: BluetoothDevice) {
        closeSocketOnly()
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _connectedDeviceName.value = device.name ?: device.address

        try {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            outputStream = s.outputStream
            inputStream = s.inputStream
            _connectionStatus.value = ConnectionStatus.CONNECTED
            Log.d(TAG, "Bluetooth connected: ${device.name}")

            acquireWakeLock()
            startReceiveLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth connection failed: ${e.message}")
            _connectionStatus.value = ConnectionStatus.ERROR
            closeSocketOnly()
            triggerAutoReconnect()
        }
    }

    fun disconnect(isManual: Boolean = true) {
        if (isManual) {
            isAutoReconnectActive = false
            targetDevice = null
            reconnectJob?.cancel()
        }
        activeSendJob?.cancel()
        // 順番待ち中のキュー送信も一緒にキャンセルする（切断後に古い内容が送られないように）
        synchronized(queuedSendJobs) {
            queuedSendJobs.forEach { it.cancel() }
            queuedSendJobs.clear()
        }
        closeSocketOnly()
        releaseWakeLock()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _connectedDeviceName.value = null
    }

    private fun closeSocketOnly() {
        receiveJob?.cancel()
        receiveJob = null
        try { inputStream?.close() } catch (e: Exception) {}
        try { outputStream?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
        clearPendingImageSends()
    }

    private fun triggerAutoReconnect() {
        if (!isAutoReconnectActive || targetDevice == null) return
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            while (isActive && isAutoReconnectActive && _connectionStatus.value != ConnectionStatus.CONNECTED) {
                delay(3000)
                val dev = targetDevice ?: break
                connectMutex.withLock {
                    if (isAutoReconnectActive && _connectionStatus.value != ConnectionStatus.CONNECTED) {
                        Log.d(TAG, "Auto-reconnecting to ${dev.name ?: dev.address}...")
                        internalConnect(dev)
                    }
                }
            }
        }
    }

    private fun startReceiveLoop() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val stream = inputStream ?: return@launch
            var state = 0
            var packetType: Byte = 0
            var packetLen = 0
            var lenBytesRead = 0
            val lenBuf = ByteArray(4)
            var payload = ByteArray(0)
            var payloadIndex = 0

            try {
                while (isActive) {
                    val b = stream.read()
                    if (b == -1) break

                    when (state) {
                        0 -> if (b == 0xAA) state = 1
                        1 -> if (b == 0x55) state = 2 else if (b != 0xAA) state = 0
                        2 -> {
                            packetType = b.toByte()
                            lenBytesRead = 0
                            packetLen = 0
                            state = 3
                        }
                        3 -> {
                            lenBuf[lenBytesRead++] = b.toByte()
                            if (lenBytesRead == 4) {
                                packetLen = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                                            ((lenBuf[1].toInt() and 0xFF) shl 16) or
                                            ((lenBuf[2].toInt() and 0xFF) shl 8) or
                                            (lenBuf[3].toInt() and 0xFF)
                                if (packetLen in 1..256) {
                                    payload = ByteArray(packetLen)
                                    payloadIndex = 0
                                    state = 4
                                } else if (packetLen == 0) {
                                    handleReceivedPacket(packetType, ByteArray(0))
                                    state = 0
                                } else {
                                    state = 0
                                }
                            }
                        }
                        4 -> {
                            payload[payloadIndex++] = b.toByte()
                            if (payloadIndex >= packetLen) {
                                handleReceivedPacket(packetType, payload)
                                state = 0
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "SPP Receive loop ended: ${e.message}")
            } finally {
                if (_connectionStatus.value == ConnectionStatus.CONNECTED) {
                    _connectionStatus.value = ConnectionStatus.ERROR
                    closeSocketOnly()
                    triggerAutoReconnect()
                }
            }
        }
    }

    private fun handleReceivedPacket(type: Byte, payload: ByteArray) {
        if (type == 0x04.toByte() && payload.isNotEmpty()) {
            val cmd = payload[0]
            Log.d(TAG, "Media Control Command: 0x%02X".format(cmd))
            onCommandReceived?.invoke(cmd)
        }
        if (type == 0x05.toByte()) {
            handleImageAck(payload)
        }
    }

    // ★ ESP と同一の定義: ペイロード全バイトを符号なしで加算した値 (Int のオーバーフローで uint32 相当)
    private fun computeImageChecksum(bytes: ByteArray): Int {
        var sum = 0
        for (b in bytes) {
            sum += b.toInt() and 0xFF
        }
        return sum
    }

    // ★ レコードを保留キューの末尾 (最新側) へ登録する。上限を超えたら最古を失効させる
    private fun registerPendingImageSend(record: ImageSendRecord) {
        val evicted = mutableListOf<ImageSendRecord>()
        synchronized(pendingImageLock) {
            pendingImageSends.add(record)
            while (pendingImageSends.size > MAX_PENDING_IMAGE_SENDS) {
                evicted.add(pendingImageSends.removeAt(0))
            }
        }
        // あふれた最古レコードは回復対象から外れるため、見張り・再送を止める
        evicted.forEach { retireImageSendRecord(it) }
    }

    // ★ キューから外れたレコードの見張り・再送ジョブを止める
    private fun retireImageSendRecord(record: ImageSendRecord) {
        record.watchdogJob?.cancel()
        record.watchdogJob = null
        record.resendJob?.cancel()
        record.resendJob = null
    }

    private fun findPendingImageSend(seq: Long): ImageSendRecord? =
        synchronized(pendingImageLock) { pendingImageSends.firstOrNull { it.seq == seq } }

    private fun isPendingImageSend(record: ImageSendRecord): Boolean =
        synchronized(pendingImageLock) { pendingImageSends.any { it === record } }

    // ★ 切断時: 保留レコードを全て破棄し、見張り・再送も止める
    private fun clearPendingImageSends() {
        val all = synchronized(pendingImageLock) {
            val copy = pendingImageSends.toList()
            pendingImageSends.clear()
            copy
        }
        all.forEach { retireImageSendRecord(it) }
    }

    // ★ 画像 ACK (type 0x05): [0]=ステータス (0x01=OK / 0x00=NG), [1..4]=受信側チェックサム (BE)
    //
    // ESP は受信順 (FIFO) にフレームを処理して ACK するため、ACK の適用先は
    // 「最新」ではなく保留キューの最古レコードが基本になる。
    private fun handleImageAck(payload: ByteArray) {
        if (payload.size != 5) {
            Log.w(TAG, "Invalid image ACK payload size: ${payload.size}")
            return
        }
        val status = payload[0].toInt() and 0xFF
        val ackChecksum = ((payload[1].toInt() and 0xFF) shl 24) or
                ((payload[2].toInt() and 0xFF) shl 16) or
                ((payload[3].toInt() and 0xFF) shl 8) or
                (payload[4].toInt() and 0xFF)
        if (status == 0x01) {
            // OK: csum が一致したレコードは ESP が処理済み。ESP は FIFO 処理なので、
            // 一致した位置までのレコードは全て処理済みのはずであり、まとめて完了と
            // して除去する。一致するレコードが無い OK は、既に失効したフレームへの
            // 遅延 ACK として無視する。
            val completed = mutableListOf<ImageSendRecord>()
            synchronized(pendingImageLock) {
                val idx = pendingImageSends.indexOfFirst { it.checksum == ackChecksum }
                if (idx >= 0) {
                    repeat(idx + 1) { completed.add(pendingImageSends.removeAt(0)) }
                }
            }
            if (completed.isEmpty()) {
                Log.w(TAG, "Image ACK OK without matching pending record: csum=%08X".format(ackChecksum))
            } else {
                completed.forEach { rec ->
                    synchronized(rec) { rec.acked = true }
                    retireImageSendRecord(rec)
                }
                val matched = completed.last()
                Log.d(TAG, "Image ACK OK: seq=${matched.seq} csum=%08X (completed ${completed.size} record(s))".format(matched.checksum))
            }
        } else {
            // NG: 受信バイト列が化けた場合は ESP の csum が送信側と一致しないのが
            // 通常であり、SOI 不正・受信タイムアウト時は csum=0 が返る。いずれも
            // 照合はせず、FIFO の最古レコードが失敗したものとして再送する。
            val oldest = synchronized(pendingImageLock) { pendingImageSends.firstOrNull() }
            if (oldest == null) {
                Log.w(TAG, "Image ACK NG without pending record: received csum=%08X".format(ackChecksum))
                return
            }
            Log.w(TAG, "Image ACK NG for oldest pending: seq=${oldest.seq} received csum=%08X sent csum=%08X".format(ackChecksum, oldest.checksum))
            requestImageResend(oldest, "ACK NG", ResendReason.ACK_NG)
        }
    }

    // ★ 送信完了から IMAGE_ACK_TIMEOUT_MS 経っても ACK が無ければ再送を試みる
    private fun scheduleImageAckWatch(record: ImageSendRecord) {
        record.watchdogJob?.cancel()
        record.watchdogJob = scope.launch {
            delay(IMAGE_ACK_TIMEOUT_MS)
            if (synchronized(record) { record.acked }) return@launch
            if (!isPendingImageSend(record)) return@launch
            if (_connectionStatus.value != ConnectionStatus.CONNECTED) return@launch
            // タイムアウト再送 (仮回復) は、そのレコードが「最後に送られた画像」の
            // 場合に限る。後続の画像が既に送られているのに古い画像を仮再送すると、
            // 画面が古い内容へ巻き戻ってしまうため (NG 再送は FIFO 対象なので対象外)。
            val isLastSent = synchronized(pendingImageLock) { pendingImageSends.lastOrNull() === record }
            if (!isLastSent) {
                Log.d(TAG, "Skip ACK timeout resend for superseded image: seq=${record.seq}")
                return@launch
            }
            if (synchronized(record) { record.timeoutResendUsed }) {
                Log.w(TAG, "Image ACK still not received, giving up timeout recovery: seq=${record.seq}")
                return@launch
            }
            requestImageResend(record, "ACK timeout", ResendReason.ACK_TIMEOUT)
        }
    }

    // ★ 保留キューのレコードを起因別の予算内で再送する (通常送信のキャンセル管理には干渉しない)
    private fun requestImageResend(record: ImageSendRecord, reason: String, kind: ResendReason) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) return
        // 既に ACK 完了したレコードやキューから失効したレコードは再送しない
        if (!isPendingImageSend(record)) return
        synchronized(record) {
            if (record.acked) return
            when (kind) {
                ResendReason.ACK_TIMEOUT -> {
                    if (record.timeoutResendUsed) return
                    record.timeoutResendUsed = true
                }
                ResendReason.ACK_NG -> {
                    if (record.ngResendUsed) return
                    record.ngResendUsed = true
                }
            }
        }
        Log.d(TAG, "Resending image: seq=${record.seq} reason=$reason")
        // 再送ジョブはレコードに保持し、レコードの失効・切断時にキャンセルできるようにする
        record.resendJob?.cancel()
        record.resendJob = scope.launch {
            sendPacketBody(
                record.title,
                record.artist,
                record.album,
                record.jpegBytes,
                record.rgb565Color,
                record.rgb565BgColor,
                resendOfSeq = record.seq
            )
        }
    }

    /**
     * ★ 古い送信ジョブをキャンセルし、常に最新のパケットのみを高速送信
     *
     * ただしキャンセルが効くのはパケット境界のみで、書き込み途中のフレームは
     * 打ち切らずに送り切る (sendPacketBody 内の NonCancellable 区間を参照)。
     */
    fun sendMediaPacket(
        title: String,
        artist: String,
        album: String,
        jpegBytes: ByteArray?,
        rgb565Color: Int = 0x067F,
        rgb565BgColor: Int = 0x0000
    ) {
        // 現在送信中のジョブがあれば破棄して新しい曲を優先
        activeSendJob?.cancel()

        activeSendJob = scope.launch {
            sendPacketBody(title, artist, album, jpegBytes, rgb565Color, rgb565BgColor)
        }
    }

    /**
     * ★ 通知ポップアップ用: 送信中ジョブをキャンセルせず、送信が空くまで待ってから送る
     *
     * 曲/スタンバイ送信を途中で切るとフレームが途中で切れて ESP 側が後続パケットを
     * 解釈できず、ポップアップ画像が描画されなくなるため、ポップアップはこちらを使う。
     * 通常送信の「最新優先でキャンセル」挙動は変えない。
     */
    fun sendMediaPacketQueued(
        title: String,
        artist: String,
        album: String,
        jpegBytes: ByteArray?,
        rgb565Color: Int = 0x067F,
        rgb565BgColor: Int = 0x0000
    ) {
        // キャンセル待ち行列化: activeSendJob には触れず、sendMutex が空くまで待つ
        val enqueuedAtMs = System.currentTimeMillis()
        val job = scope.launch {
            // ★ queuedAtMs は必ず名前付き引数で渡すこと。位置引数で渡すと後ろの
            // resendOfSeq に束縛され、鮮度ガードが効かなくなるうえ、送信が「再送扱い」
            // になって ACK 監視・再送がポップアップ画像に対して無効化されてしまう。
            sendPacketBody(title, artist, album, jpegBytes, rgb565Color, rgb565BgColor, queuedAtMs = enqueuedAtMs)
        }
        // Job を保持し、切断時にキャンセルできるようにする。完了したら自動で外す
        queuedSendJobs.add(job)
        job.invokeOnCompletion { queuedSendJobs.remove(job) }
    }

    private suspend fun sendPacketBody(
        title: String,
        artist: String,
        album: String,
        jpegBytes: ByteArray?,
        rgb565Color: Int,
        rgb565BgColor: Int,
        resendOfSeq: Long? = null,
        queuedAtMs: Long? = null
    ) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED || outputStream == null) return

        sendMutex.withLock {
            try {
                val out = outputStream ?: return@withLock
                if (!currentCoroutineContext().isActive) return@withLock

                // sendMutex 待ちの間に切断→再接続が起きていた場合は送らない。
                // 再接続後の新しいソケットへ古い内容を書き込むのを防ぐ。
                if (_connectionStatus.value != ConnectionStatus.CONNECTED) return@withLock

                // 鮮度ガード: キュー送信が10秒以上滞留していたら、古いポップアップとして破棄する
                if (queuedAtMs != null && System.currentTimeMillis() - queuedAtMs > 10_000L) {
                    Log.d(TAG, "Dropped stale queued packet (over 10s old)")
                    return@withLock
                }

                // 再送の場合、Mutex を掴んだ時点でも対象レコードがまだ保留中か再確認する。
                // 待ちの間に ACK 完了・切断・キューあふれで失効していたら何も書かずに終わる
                // (失効した古いポップアップ画像が画面に復活してしまうのを防ぐ)。
                if (resendOfSeq != null && findPendingImageSend(resendOfSeq) == null) {
                    Log.d(TAG, "Resend aborted, record no longer pending: seq=$resendOfSeq")
                    return@withLock
                }

                // 1. メタデータ送信
                val metadataMap = mapOf(
                    "title" to title,
                    "artist" to artist,
                    "album" to album,
                    "color" to rgb565Color,
                    "bg" to rgb565BgColor
                )
                val jsonBytes = gson.toJson(metadataMap).toByteArray(Charsets.UTF_8)
                val metaPacket = buildPacket(0x01.toByte(), jsonBytes)

                // ★ フレームの書き込みは NonCancellable で行い、キャンセルはパケット
                // 境界でのみ効かせる。ヘッダを書き始めたフレームを途中で打ち切ると、
                // ESP 側がフレームの残りバイトを後続パケットのバイトで埋めてしまい
                // (スプライス)、その後続パケットまで化けるため、書き始めたフレーム
                // (ヘッダ+ペイロード) は必ず完結させる。
                withContext(NonCancellable) {
                    out.write(metaPacket)
                    out.flush()

                    delay(100)

                    // 2. JPEGパケット送信 (チャンク256B, flushはループ完了後に1回のみ)
                    if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                        // ★ ACK 監視用に今回の送信を保留キューへ記録する
                        // (再送時は既存のレコードを引き継ぎ、新規 seq は振らない)
                        val sendRecord: ImageSendRecord? = if (resendOfSeq == null) {
                            val rec = ImageSendRecord(
                                seq = ++imageSendSeq,
                                title = title,
                                artist = artist,
                                album = album,
                                jpegBytes = jpegBytes,
                                rgb565Color = rgb565Color,
                                rgb565BgColor = rgb565BgColor,
                                checksum = computeImageChecksum(jpegBytes)
                            )
                            registerPendingImageSend(rec)
                            rec
                        } else {
                            findPendingImageSend(resendOfSeq)
                        }
                        val imgPacket = buildPacket(0x02.toByte(), jpegBytes)
                        val sendStartMs = System.currentTimeMillis()
                        var offset = 0
                        val chunkSize = 256
                        while (offset < imgPacket.size) {
                            val len = minOf(chunkSize, imgPacket.size - offset)
                            out.write(imgPacket, offset, len)
                            offset += len
                            if (offset < imgPacket.size) delay(10)
                        }
                        out.flush()
                        val elapsedMs = System.currentTimeMillis() - sendStartMs
                        Log.d(TAG, "Image sent: ${imgPacket.size} bytes in $elapsedMs ms")
                        // ★ ESP からの画像 ACK (type 0x05) を監視する
                        if (sendRecord != null) {
                            scheduleImageAckWatch(sendRecord)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Failed to send media packet: ${e.message}")
                    _connectionStatus.value = ConnectionStatus.ERROR
                    closeSocketOnly()
                    triggerAutoReconnect()
                }
            }
        }
    }

    private fun buildPacket(type: Byte, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(2 + 1 + 4 + payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(0xAA.toByte())
        buffer.put(0x55.toByte())
        buffer.put(type)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }
}