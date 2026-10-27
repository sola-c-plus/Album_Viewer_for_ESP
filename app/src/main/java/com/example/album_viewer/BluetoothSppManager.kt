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
import java.util.UUID

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

object BluetoothSppManager {
    private const val TAG = "BluetoothSppManager"
    private const val IMAGE_ACK_TIMEOUT_MS = 3000L
    private const val MAX_IMAGE_RESEND = 1
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

    // ★ 画像 ACK＋再送プロトコル (type 0x05): 最新の画像送信を1件だけ記録する
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
        var resendCount: Int = 0
        var acked: Boolean = false
    }

    @Volatile
    private var latestImageSend: ImageSendRecord? = null
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
        latestImageSend = null
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

    // ★ 画像 ACK (type 0x05): [0]=ステータス (0x01=OK / 0x00=NG), [1..4]=受信側チェックサム (BE)
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
        val record = latestImageSend ?: return
        if (status == 0x01) {
            if (ackChecksum == record.checksum) {
                synchronized(record) { record.acked = true }
                Log.d(TAG, "Image ACK OK: seq=${record.seq} csum=%08X".format(record.checksum))
            } else {
                Log.w(TAG, "Image ACK csum mismatch: seq=${record.seq} ack=%08X sent=%08X".format(ackChecksum, record.checksum))
            }
        } else {
            Log.w(TAG, "Image ACK NG: seq=${record.seq} received csum=%08X".format(ackChecksum))
            requestImageResend(record, "ACK NG")
        }
    }

    // ★ 送信完了から IMAGE_ACK_TIMEOUT_MS 経っても ACK が無ければ再送を試みる
    private fun scheduleImageAckWatch(seq: Long, allowResend: Boolean) {
        scope.launch {
            delay(IMAGE_ACK_TIMEOUT_MS)
            val record = latestImageSend ?: return@launch
            if (record.seq != seq || record.acked) return@launch
            if (_connectionStatus.value != ConnectionStatus.CONNECTED) return@launch
            if (allowResend) {
                requestImageResend(record, "ACK timeout")
            } else {
                Log.w(TAG, "Image ACK not received after resend, giving up: seq=$seq")
            }
        }
    }

    // ★ 最新 seq の画像を最大 MAX_IMAGE_RESEND 回だけ再送する (通常送信のキャンセル管理には干渉しない)
    private fun requestImageResend(record: ImageSendRecord, reason: String) {
        if (latestImageSend !== record) return
        if (_connectionStatus.value != ConnectionStatus.CONNECTED) return
        synchronized(record) {
            if (record.acked || record.resendCount >= MAX_IMAGE_RESEND) return
            record.resendCount++
        }
        Log.d(TAG, "Resending image: seq=${record.seq} reason=$reason")
        scope.launch {
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
     * ★ 古い送信ジョブを即座にキャンセルし、常に最新のパケットのみを高速送信
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
        scope.launch {
            sendPacketBody(title, artist, album, jpegBytes, rgb565Color, rgb565BgColor)
        }
    }

    private suspend fun sendPacketBody(
        title: String,
        artist: String,
        album: String,
        jpegBytes: ByteArray?,
        rgb565Color: Int,
        rgb565BgColor: Int,
        resendOfSeq: Long? = null
    ) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED || outputStream == null) return

        sendMutex.withLock {
            try {
                val out = outputStream ?: return@withLock
                if (!currentCoroutineContext().isActive) return@withLock

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
                out.write(metaPacket)
                out.flush()

                delay(100)

                // 2. JPEGパケット送信 (チャンク256B, flushはループ完了後に1回のみ)
                if (jpegBytes != null && jpegBytes.isNotEmpty() && currentCoroutineContext().isActive) {
                    // ★ ACK 監視用に今回の送信を記録する (再送時は既存の記録を引き継ぎ、新規 seq は振らない)
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
                        latestImageSend = rec
                        rec
                    } else {
                        latestImageSend?.takeIf { it.seq == resendOfSeq }
                    }
                    val imgPacket = buildPacket(0x02.toByte(), jpegBytes)
                    val sendStartMs = System.currentTimeMillis()
                    var offset = 0
                    val chunkSize = 256
                    while (offset < imgPacket.size && currentCoroutineContext().isActive) {
                        val len = minOf(chunkSize, imgPacket.size - offset)
                        out.write(imgPacket, offset, len)
                        offset += len
                        if (offset < imgPacket.size) delay(10)
                    }
                    if (offset >= imgPacket.size) {
                        out.flush()
                        val elapsedMs = System.currentTimeMillis() - sendStartMs
                        Log.d(TAG, "Image sent: ${imgPacket.size} bytes in $elapsedMs ms")
                        // ★ ESP からの画像 ACK (type 0x05) を監視する
                        if (sendRecord != null) {
                            scheduleImageAckWatch(sendRecord.seq, allowResend = resendOfSeq == null)
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