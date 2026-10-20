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
            if (_connectionStatus.value != ConnectionStatus.CONNECTED || outputStream == null) return@launch

            sendMutex.withLock {
                try {
                    val out = outputStream ?: return@withLock
                    if (!isActive) return@withLock

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

                    // 2. JPEGパケット送信 (チャンク256B, ディレイ5msで0.3秒以内に完了)
                    if (jpegBytes != null && jpegBytes.isNotEmpty() && isActive) {
                        val imgPacket = buildPacket(0x02.toByte(), jpegBytes)
                        var offset = 0
                        val chunkSize = 256
                        while (offset < imgPacket.size && isActive) {
                            val len = minOf(chunkSize, imgPacket.size - offset)
                            out.write(imgPacket, offset, len)
                            out.flush()
                            offset += len
                            delay(5)
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
    }

    fun sendTimeSyncPacket(hour: Int, minute: Int, second: Int) {
        scope.launch {
            if (_connectionStatus.value != ConnectionStatus.CONNECTED || outputStream == null) return@launch
            if (!sendMutex.tryLock()) return@launch

            try {
                val timeMap = mapOf("h" to hour, "m" to minute, "s" to second)
                val jsonBytes = gson.toJson(timeMap).toByteArray(Charsets.UTF_8)
                val timePacket = buildPacket(0x03.toByte(), jsonBytes)
                val out = outputStream ?: return@launch
                out.write(timePacket)
                out.flush()
            } catch (e: Exception) {
                // ignore
            } finally {
                sendMutex.unlock()
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