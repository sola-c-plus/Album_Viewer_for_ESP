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
import java.util.zip.CRC32

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

// ★ プロトコル v2 ハンドシェイクの状態 (設計書 design-album-viewer-rewrite.md §3.2 / §5.3)
// RFCOMM 接続そのものは ConnectionStatus が表し、こちらは「v2 で会話できるか」を表す。
// 版不一致の間は RFCOMM がつながっていても転送を一切行わない。
enum class HandshakeStatus {
    IDLE,             // 未実施 (未接続)
    NEGOTIATING,      // HELLO を送り HELLO_ACK を待っている
    READY,            // 版一致。転送可能
    VERSION_MISMATCH  // 版不一致 (相手が v1 ファーム等)
}

object BluetoothSppManager {
    private const val TAG = "BluetoothSppManager"

    // ===== プロトコル v2 定数 (設計書 §3 / §8 の決定事項) =====
    private const val PROTO_VERSION = 0x02

    private const val TYPE_CMD = 0x04       // ESP→スマホ: 再生操作コマンド (現行維持)
    private const val TYPE_BEGIN = 0x06     // スマホ→ESP: 転送開始
    private const val TYPE_CHUNK = 0x07     // スマホ→ESP: チャンク
    private const val TYPE_XFER_ACK = 0x08  // ESP→スマホ: 転送 ACK
    private const val TYPE_ABORT = 0x0A     // 双方向: 転送中断
    private const val TYPE_HELLO = 0x0B     // スマホ→ESP: ハンドシェイク
    private const val TYPE_HELLO_ACK = 0x0C // ESP→スマホ: ハンドシェイク応答
    // type 0x01 (生 META) / 0x02 (生 JPEG) / 0x05 (v1 画像 ACK) は v2 では廃止。

    private const val KIND_META = 0x01
    private const val KIND_JPEG = 0x02

    private const val XFER_ACK_READY = 0x01        // READY / PROGRESS (nextChunk = 次に欲しい番号)
    private const val XFER_ACK_COMPLETE_OK = 0x02
    private const val XFER_ACK_COMPLETE_NG_CRC = 0x03
    private const val XFER_ACK_COMPLETE_NG_FORMAT = 0x04
    private const val XFER_ACK_ABORTED = 0x05

    // ABORT の reason は設計書で未規定のため本実装で定義する。
    // ESP 側は reason を解釈せず、transferId の転送を破棄するだけでよい。
    private const val ABORT_REASON_CANCELLED = 0x01       // 新しい送信によるキャンセル等
    private const val ABORT_REASON_RETRY_EXHAUSTED = 0x02 // 再試行上限到達
    private const val ABORT_REASON_DEADLINE = 0x03        // 転送総期限超過

    private const val CHUNK_PAYLOAD_SIZE = 1024          // §8 D1
    private const val HELLO_TIMEOUT_MS = 1000L           // §3.2
    private const val HELLO_MAX_RESENDS = 3              // §3.2「最大3回再送」= 初回 + 再送3回
    private const val BEGIN_ACK_TIMEOUT_MS = 1000L       // §3.4
    private const val BEGIN_MAX_RESENDS = 3              // §3.4「最大3回再送」= 初回 + 再送3回
    private const val CHUNK_ACK_TIMEOUT_MS = 1000L       // §3.4
    private const val CHUNK_MAX_RETRIES = 5              // §3.4 (1チャンク位置あたりの再送上限)
    private const val TRANSFER_DEADLINE_MS = 20_000L     // §5.2 (転送全体の安全網)
    private const val WRITE_SUBCHUNK_SIZE = 256          // §8 D10: チャンクフレーム内の小分け書き込み
    private const val WRITE_SUBCHUNK_INTERVAL_MS = 10L   // §8 D10
    private const val QUEUED_STALE_MS = 10_000L          // 鮮度ガード (現行維持)

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _handshakeStatus = MutableStateFlow(HandshakeStatus.IDLE)
    val handshakeStatus: StateFlow<HandshakeStatus> = _handshakeStatus.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var receiveJob: Job? = null
    private var reconnectJob: Job? = null
    private var activeSendJob: Job? = null
    private val queuedSendJobs = Collections.synchronizedList(mutableListOf<Job>())

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val sendMutex = Mutex()
    private val connectMutex = Mutex()

    private var targetDevice: BluetoothDevice? = null
    private var isAutoReconnectActive = true
    private var wakeLock: PowerManager.WakeLock? = null
    private var appContext: Context? = null

    // 接続セッションの世代。closeSocketOnly のたびに進み、旧セッションで投入された
    // 送信ジョブが再接続後の新しいソケットへ書くことを防ぐ (PR #20 の
    // 「ストリーム同一性チェック」の形を変えた後継)。
    @Volatile
    private var sessionGeneration = 0

    // transferId は 16bit 連番で 0 を使わない (設計書 §3.7)。
    // 採番は sendMutex で直列化された転送エンジン内でのみ行うため、追加の同期は不要。
    private var transferIdCounter = 0

    private fun nextTransferId(): Int {
        transferIdCounter = if (transferIdCounter >= 0xFFFF) 1 else transferIdCounter + 1
        return transferIdCounter
    }

    // ===== ACK / HELLO_ACK の受け渡し (受信ループ → 転送エンジン / ハンドシェイク) =====
    // 同時に待つのは常に1件だけ (転送は sendMutex で直列化され、ハンドシェイクは接続直後)。
    // transferId が一致しない ACK は無視する (設計書 §3.7)。

    private data class AckFrame(val transferId: Int, val nextChunk: Int, val status: Int)

    private val ackLock = Any()
    private var ackExpectedId = -1
    private var ackWaiter: CompletableDeferred<AckFrame>? = null

    private fun expectAck(transferId: Int): CompletableDeferred<AckFrame> {
        val d = CompletableDeferred<AckFrame>()
        synchronized(ackLock) {
            ackExpectedId = transferId
            ackWaiter = d
        }
        return d
    }

    private fun clearAckExpect(d: CompletableDeferred<AckFrame>) {
        synchronized(ackLock) {
            if (ackWaiter === d) {
                ackWaiter = null
                ackExpectedId = -1
            }
        }
    }

    private fun deliverAck(frame: AckFrame) {
        val d = synchronized(ackLock) {
            if (ackWaiter != null && ackExpectedId == frame.transferId) {
                val w = ackWaiter
                ackWaiter = null
                ackExpectedId = -1
                w
            } else null
        }
        d?.complete(frame)
    }

    // 切断時に待ちを即座に失敗扱いでほどく (タイムアウトまでぶら下がらないように)
    private fun cancelAckWaiter() {
        val pending = synchronized(ackLock) {
            val w = ackWaiter
            val id = ackExpectedId
            ackWaiter = null
            ackExpectedId = -1
            if (w != null) Pair(w, id) else null
        }
        pending?.let { it.first.complete(AckFrame(it.second, -1, XFER_ACK_ABORTED)) }
    }

    private val helloLock = Any()
    private var helloWaiter: CompletableDeferred<ByteArray>? = null

    private fun expectHello(): CompletableDeferred<ByteArray> {
        val d = CompletableDeferred<ByteArray>()
        synchronized(helloLock) { helloWaiter = d }
        return d
    }

    private fun clearHelloExpect(d: CompletableDeferred<ByteArray>) {
        synchronized(helloLock) {
            if (helloWaiter === d) helloWaiter = null
        }
    }

    private fun deliverHello(payload: ByteArray) {
        val d = synchronized(helloLock) {
            val w = helloWaiter
            helloWaiter = null
            w
        }
        d?.complete(payload)
    }

    private fun cancelHelloWaiter() {
        val d = synchronized(helloLock) {
            val w = helloWaiter
            helloWaiter = null
            w
        }
        // 空ペイロードは「応答なし」として扱わせる
        d?.complete(ByteArray(0))
    }

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
            // ★ 転送を始める前に必ずハンドシェイクする (設計書 §3.2)。
            // 成功するまで転送 API は即時失敗を返す。再接続のたびにここから始める。
            performHandshake(s.outputStream)
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
        sessionGeneration++
        _handshakeStatus.value = HandshakeStatus.IDLE
        cancelAckWaiter()
        cancelHelloWaiter()
        receiveJob?.cancel()
        receiveJob = null
        try { inputStream?.close() } catch (e: Exception) {}
        try { outputStream?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
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

    // ===== ハンドシェイク (設計書 §3.2) =====
    // HELLO を送り、HELLO_ACK を 1,000ms 待つ。応答がなければ最大3回再送する。
    // 相手が v1 ファームの場合、HELLO (type 0x0B) は未知 type として無言で捨てられる
    // ため HELLO_ACK は永遠に来ず、タイムアウトで版不一致と判定できる。
    private suspend fun performHandshake(out: OutputStream) {
        _handshakeStatus.value = HandshakeStatus.NEGOTIATING
        try {
            var resends = 0
            while (true) {
                if (_connectionStatus.value != ConnectionStatus.CONNECTED || outputStream !== out) {
                    _handshakeStatus.value = HandshakeStatus.IDLE
                    return
                }
                val wait = expectHello()
                val ackPayload = try {
                    writeFrameWhole(out, buildPacket(TYPE_HELLO.toByte(), byteArrayOf(PROTO_VERSION.toByte())))
                    withTimeoutOrNull(HELLO_TIMEOUT_MS) { wait.await() }
                } finally {
                    clearHelloExpect(wait)
                }
                if (ackPayload != null && ackPayload.isNotEmpty()) {
                    // HELLO_ACK: [protoVer 1B][status 1B] (status 0x01 = 一致 / 0x00 = 非対応)
                    val respVer = ackPayload[0].toInt() and 0xFF
                    val respStatus = if (ackPayload.size >= 2) ackPayload[1].toInt() and 0xFF else -1
                    val ok = ackPayload.size >= 2 && respVer == PROTO_VERSION && respStatus == 0x01
                    _handshakeStatus.value = if (ok) HandshakeStatus.READY else HandshakeStatus.VERSION_MISMATCH
                    Log.d(TAG, "Handshake done: result=${_handshakeStatus.value} ver=$respVer status=$respStatus")
                    return
                }
                resends++
                if (resends > HELLO_MAX_RESENDS) {
                    _handshakeStatus.value = HandshakeStatus.VERSION_MISMATCH
                    Log.w(TAG, "Handshake failed: no HELLO_ACK (version mismatch)")
                    return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ソケットが死んだ等。接続状態の後処理 (ERROR 化＋再接続) は受信ループ側に委ねる
            Log.w(TAG, "Handshake aborted: ${e.message}")
            if (_handshakeStatus.value == HandshakeStatus.NEGOTIATING) {
                _handshakeStatus.value = HandshakeStatus.IDLE
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
        when (type) {
            TYPE_CMD.toByte() -> {
                if (payload.isNotEmpty()) {
                    val cmd = payload[0]
                    Log.d(TAG, "Media Control Command: 0x%02X".format(cmd))
                    onCommandReceived?.invoke(cmd)
                }
            }
            TYPE_XFER_ACK.toByte() -> handleXferAck(payload)
            TYPE_HELLO_ACK.toByte() -> deliverHello(payload)
            TYPE_ABORT.toByte() -> handleAbortFrame(payload)
            // 0x05 (v1 画像 ACK) を含む未知 type はそのまま読み捨てる
        }
    }

    // ★ XFER_ACK (type 0x08): [transferId 2B BE][nextChunk 2B BE][status 1B]
    private fun handleXferAck(payload: ByteArray) {
        if (payload.size != 5) {
            Log.w(TAG, "Invalid XFER_ACK payload size: ${payload.size}")
            return
        }
        val transferId = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val nextChunk = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        val status = payload[4].toInt() and 0xFF
        deliverAck(AckFrame(transferId, nextChunk, status))
    }

    // ★ ABORT (type 0x0A): [transferId 2B BE][reason 1B]
    // ESP 都合の中断は XFER_ACK の ABORTED と同じ扱いで転送エンジンへ渡す。
    private fun handleAbortFrame(payload: ByteArray) {
        if (payload.size != 3) {
            Log.w(TAG, "Invalid ABORT payload size: ${payload.size}")
            return
        }
        val transferId = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val reason = payload[2].toInt() and 0xFF
        Log.w(TAG, "ABORT received from ESP: id=$transferId reason=$reason")
        deliverAck(AckFrame(transferId, -1, XFER_ACK_ABORTED))
    }

    /**
     * ★ 古い送信ジョブをキャンセルし、常に最新の内容を優先して送る (曲・スタンバイ用)
     *
     * 転送結果 (成功/失敗) は onResult で1回だけ通知される。失敗の内訳
     * (未接続・ハンドシェイク未成立・鮮度切れ・ESP の検証 NG 等) は区別しない。
     * キャンセルされた場合も onResult(false) が呼ばれる。
     */
    fun sendMediaPacket(
        title: String,
        artist: String,
        album: String,
        jpegBytes: ByteArray?,
        rgb565Color: Int = 0x067F,
        rgb565BgColor: Int = 0x0000,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        // 現在送信中のジョブがあれば破棄して新しい内容を優先。
        // キャンセルが効くのはチャンク境界のみ (転送エンジン側で ABORT を送って止まる)。
        activeSendJob?.cancel()

        val gen = sessionGeneration
        activeSendJob = scope.launch {
            var result = false
            try {
                result = executeSend(title, artist, album, jpegBytes, rgb565Color, rgb565BgColor, queuedAtMs = null, sessionGen = gen)
            } catch (e: CancellationException) {
                result = false
                throw e
            } finally {
                onResult?.invoke(result)
            }
        }
    }

    /**
     * ★ 通知ポップアップ用: 送信中ジョブをキャンセルせず、送信が空くまで待ってから送る
     *
     * 曲/スタンバイ送信と違い、ポップアップはイベント性コンテンツのため順番待ちにする。
     * 結果は sendMediaPacket と同じく onResult で1回だけ通知される。
     */
    fun sendMediaPacketQueued(
        title: String,
        artist: String,
        album: String,
        jpegBytes: ByteArray?,
        rgb565Color: Int = 0x067F,
        rgb565BgColor: Int = 0x0000,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        // キャンセル待ち行列化: activeSendJob には触れず、sendMutex が空くまで待つ
        val enqueuedAtMs = System.currentTimeMillis()
        val gen = sessionGeneration
        val job = scope.launch {
            var result = false
            try {
                // ★ queuedAtMs は必ず名前付き引数で渡すこと。位置引数で渡すと別の
                // パラメータに束縛され、鮮度ガードが効かなくなる (棚卸し §10 注意点1)。
                result = executeSend(title, artist, album, jpegBytes, rgb565Color, rgb565BgColor, queuedAtMs = enqueuedAtMs, sessionGen = gen)
            } catch (e: CancellationException) {
                result = false
                throw e
            } finally {
                onResult?.invoke(result)
            }
        }
        // Job を保持し、切断時にキャンセルできるようにする。完了したら自動で外す
        queuedSendJobs.add(job)
        job.invokeOnCompletion { queuedSendJobs.remove(job) }
    }

    // 送信1件の実体: META 転送 → JPEG 転送 (あれば) を直列に実行し、
    // 両方 COMPLETE_OK になった場合のみ true を返す。
    private suspend fun executeSend(
        title: String,
        artist: String,
        album: String,
        jpegBytes: ByteArray?,
        rgb565Color: Int,
        rgb565BgColor: Int,
        queuedAtMs: Long?,
        sessionGen: Int
    ): Boolean {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED || outputStream == null) return false

        return try {
            sendMutex.withLock {
                val out = outputStream ?: return@withLock false
                if (!currentCoroutineContext().isActive) return@withLock false

                // sendMutex 待ちの間に切断→再接続が起きていた場合は送らない。
                // 再接続後の新しいソケットへ古い内容を書き込むのを防ぐ。
                if (_connectionStatus.value != ConnectionStatus.CONNECTED) return@withLock false
                if (outputStream !== out) return@withLock false
                if (sessionGen != sessionGeneration) return@withLock false

                // 鮮度ガード: キュー送信が10秒以上滞留していたら、古いポップアップとして破棄する (現行維持)
                if (queuedAtMs != null && System.currentTimeMillis() - queuedAtMs > QUEUED_STALE_MS) {
                    Log.d(TAG, "Dropped stale queued packet (over 10s old)")
                    return@withLock false
                }

                // ハンドシェイクが成立するまで転送は即時失敗 (設計書 §3.2)。
                // 曲・スタンバイは署名が未コミットのまま残り、次の監視ティックで再送される。
                if (_handshakeStatus.value != HandshakeStatus.READY) {
                    Log.d(TAG, "Transfer skipped: handshake not ready (${_handshakeStatus.value})")
                    return@withLock false
                }

                // 1. メタデータ転送 (常に先行。ESP では検証通過後にだけ適用される)
                val metadataMap = mapOf(
                    "title" to title,
                    "artist" to artist,
                    "album" to album,
                    "color" to rgb565Color,
                    "bg" to rgb565BgColor
                )
                val metaBytes = gson.toJson(metadataMap).toByteArray(Charsets.UTF_8)
                if (!runTransfer(out, sessionGen, KIND_META, metaBytes)) {
                    Log.w(TAG, "META transfer failed: title=$title")
                    return@withLock false
                }

                // 2. JPEG 転送
                if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                    if (!runTransfer(out, sessionGen, KIND_JPEG, jpegBytes)) {
                        Log.w(TAG, "JPEG transfer failed: ${jpegBytes.size} bytes")
                        return@withLock false
                    }
                }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media packet: ${e.message}")
            _connectionStatus.value = ConnectionStatus.ERROR
            closeSocketOnly()
            triggerAutoReconnect()
            false
        }
    }

    // セッションが生きているか (キャンセルはここで CancellationException として伝播する)
    private suspend fun isSessionAlive(out: OutputStream, sessionGen: Int): Boolean {
        currentCoroutineContext().ensureActive()
        return _connectionStatus.value == ConnectionStatus.CONNECTED &&
            outputStream === out &&
            sessionGen == sessionGeneration
    }

    // ★ 転送エンジン: BEGIN → チャンク列 (ウィンドウ1) → COMPLETE (設計書 §3.3 / §3.4)
    // 成功 (COMPLETE_OK) の場合のみ true。キャンセル時は ABORT をベストエフォートで
    // 送ってから CancellationException を伝播する。
    private suspend fun runTransfer(out: OutputStream, sessionGen: Int, kind: Int, data: ByteArray): Boolean {
        val transferId = nextTransferId()
        val deadlineMs = System.currentTimeMillis() + TRANSFER_DEADLINE_MS
        try {
            val crc = CRC32()
            crc.update(data)
            val chunkCount = (data.size + CHUNK_PAYLOAD_SIZE - 1) / CHUNK_PAYLOAD_SIZE

            // ---- BEGIN フェーズ ----
            val beginPayload = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
                .putShort(transferId.toShort())
                .put(kind.toByte())
                .putInt(data.size)
                .putInt(crc.value.toInt())
                .putShort(chunkCount.toShort())
                .array()
            var beginResends = 0
            while (true) {
                if (!isSessionAlive(out, sessionGen)) return false
                if (System.currentTimeMillis() > deadlineMs) {
                    sendAbortBestEffort(out, transferId, ABORT_REASON_DEADLINE)
                    return false
                }
                val wait = expectAck(transferId)
                val ack = try {
                    writeFrameSubChunked(out, buildPacket(TYPE_BEGIN.toByte(), beginPayload))
                    withTimeoutOrNull(BEGIN_ACK_TIMEOUT_MS) { wait.await() }
                } finally {
                    clearAckExpect(wait)
                }
                if (ack != null && ack.status == XFER_ACK_READY && ack.nextChunk == 0) break
                if (ack != null && ack.status != XFER_ACK_READY) {
                    // COMPLETE_* / ABORTED が BEGIN 応答で来ることは通常ないが、来たら失敗として扱う
                    Log.w(TAG, "BEGIN rejected: id=$transferId status=${ack.status}")
                    return false
                }
                // タイムアウト、または READY だが nextChunk != 0 (想定外) → BEGIN を再送する
                beginResends++
                if (beginResends > BEGIN_MAX_RESENDS) {
                    Log.w(TAG, "BEGIN ack timeout: id=$transferId")
                    sendAbortBestEffort(out, transferId, ABORT_REASON_RETRY_EXHAUSTED)
                    return false
                }
            }

            // ---- チャンクフェーズ (ストップ＆ウェイト) ----
            var nextToSend = 0
            var retriesAtPosition = 0
            var retryExhausted = false
            fun countRetry() {
                retriesAtPosition++
                if (retriesAtPosition > CHUNK_MAX_RETRIES) retryExhausted = true
            }
            while (nextToSend < chunkCount) {
                if (!isSessionAlive(out, sessionGen)) return false
                if (System.currentTimeMillis() > deadlineMs) {
                    sendAbortBestEffort(out, transferId, ABORT_REASON_DEADLINE)
                    return false
                }
                val offset = nextToSend * CHUNK_PAYLOAD_SIZE
                val len = minOf(CHUNK_PAYLOAD_SIZE, data.size - offset)
                val chunkPayload = ByteBuffer.allocate(4 + len).order(ByteOrder.BIG_ENDIAN)
                    .putShort(transferId.toShort())
                    .putShort(nextToSend.toShort())
                    .put(data, offset, len)
                    .array()
                val wait = expectAck(transferId)
                val ack = try {
                    writeFrameSubChunked(out, buildPacket(TYPE_CHUNK.toByte(), chunkPayload))
                    withTimeoutOrNull(CHUNK_ACK_TIMEOUT_MS) { wait.await() }
                } finally {
                    clearAckExpect(wait)
                }
                when {
                    ack == null -> {
                        // ACK 待ちタイムアウト: 同じチャンクを再送する。
                        // 損失の単位は「1チャンク + 1秒」に収まる (旧方式はフレーム全量 + 6秒)。
                        countRetry()
                    }
                    ack.status == XFER_ACK_COMPLETE_OK -> {
                        Log.d(TAG, "Transfer complete: id=$transferId kind=$kind size=${data.size}")
                        return true
                    }
                    ack.status == XFER_ACK_COMPLETE_NG_CRC || ack.status == XFER_ACK_COMPLETE_NG_FORMAT -> {
                        // ESP 側で全量検証に落ちた。チャンク再送では直らないため転送失敗として返す
                        // (全体リトライの要否は内容層が判断する)。
                        Log.w(TAG, "Transfer rejected by ESP: id=$transferId status=${ack.status}")
                        return false
                    }
                    ack.status == XFER_ACK_ABORTED -> {
                        Log.w(TAG, "Transfer aborted by ESP: id=$transferId")
                        return false
                    }
                    ack.status == XFER_ACK_READY && ack.nextChunk == nextToSend + 1 -> {
                        nextToSend++
                        retriesAtPosition = 0
                    }
                    ack.status == XFER_ACK_READY && ack.nextChunk in 0..chunkCount -> {
                        // ACK 紛失に伴う重複 ACK 等: ESP の「次に欲しい番号」に同期し直して送り直す
                        countRetry()
                        if (!retryExhausted) nextToSend = ack.nextChunk
                    }
                    else -> countRetry()
                }
                if (retryExhausted) {
                    Log.w(TAG, "Chunk retry exhausted: id=$transferId index=$nextToSend")
                    sendAbortBestEffort(out, transferId, ABORT_REASON_RETRY_EXHAUSTED)
                    return false
                }
            }

            // 全チャンクが PROGRESS で受理されたのに COMPLETE が届いていない場合の保険。
            // 通常経路では ESP は最終チャンクに対して COMPLETE を返すため、ここに来るのは
            // COMPLETE の紛失等の異常時のみ。総期限までだけ待つ。
            while (System.currentTimeMillis() <= deadlineMs) {
                if (!isSessionAlive(out, sessionGen)) return false
                val wait = expectAck(transferId)
                val ack = try {
                    withTimeoutOrNull(CHUNK_ACK_TIMEOUT_MS) { wait.await() }
                } finally {
                    clearAckExpect(wait)
                }
                when (ack?.status) {
                    XFER_ACK_COMPLETE_OK -> return true
                    XFER_ACK_COMPLETE_NG_CRC, XFER_ACK_COMPLETE_NG_FORMAT, XFER_ACK_ABORTED -> return false
                    null -> break
                    else -> { /* PROGRESS の遅延到着等は無視して COMPLETE を待ち続ける */ }
                }
            }
            Log.w(TAG, "Transfer deadline exceeded waiting for COMPLETE: id=$transferId")
            sendAbortBestEffort(out, transferId, ABORT_REASON_DEADLINE)
            return false
        } catch (e: CancellationException) {
            // キャンセルはチャンク境界でのみ効く (書き込み自体は NonCancellable で完結させる)。
            // ESP 側に転送の残骸を残さないよう ABORT を送ってから伝播する。
            sendAbortBestEffort(out, transferId, ABORT_REASON_CANCELLED)
            throw e
        }
    }

    // フレーム書き込み: チャンクフレーム単位で NonCancellable とし、書き始めたフレームは
    // 必ず完結させる (途中で切ると ESP 側で継ぎ接ぎになる。旧方式の NonCancellable
    // 区間の考え方をチャンク単位に読み替えたもの)。大きなフレームは経路への配慮として
    // 256B ごとの小分け書き込み + 10ms 間隔にする (設計書 §8 D10)。
    private suspend fun writeFrameSubChunked(out: OutputStream, frame: ByteArray) {
        withContext(NonCancellable) {
            var offset = 0
            while (offset < frame.size) {
                val len = minOf(WRITE_SUBCHUNK_SIZE, frame.size - offset)
                out.write(frame, offset, len)
                offset += len
                if (offset < frame.size) delay(WRITE_SUBCHUNK_INTERVAL_MS)
            }
            out.flush()
        }
    }

    private suspend fun writeFrameWhole(out: OutputStream, frame: ByteArray) {
        withContext(NonCancellable) {
            out.write(frame)
            out.flush()
        }
    }

    private suspend fun sendAbortBestEffort(out: OutputStream, transferId: Int, reason: Int) {
        try {
            val payload = ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN)
                .putShort(transferId.toShort())
                .put(reason.toByte())
                .array()
            writeFrameWhole(out, buildPacket(TYPE_ABORT.toByte(), payload))
            Log.d(TAG, "ABORT sent: id=$transferId reason=$reason")
        } catch (e: Exception) {
            // ベストエフォート: 送れなくても転送の成否自体は変わらない
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
