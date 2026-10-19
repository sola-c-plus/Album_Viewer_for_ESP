package com.example.album_viewer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()
    private val sendMutex = Mutex()

    // ESP32からのボタンコマンドコールバック (0x01: Play/Pause, 0x02: Next, 0x03: Prev)
    var onCommandReceived: ((Byte) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        scope.launch {
            disconnect()
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

                startReceiveLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth connection failed", e)
                _connectionStatus.value = ConnectionStatus.ERROR
                disconnect()
            }
        }
    }

    fun disconnect() {
        receiveJob?.cancel()
        receiveJob = null
        try {
            inputStream?.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            outputStream?.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        } finally {
            socket = null
            outputStream = null
            inputStream = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _connectedDeviceName.value = null
        }
    }

    private fun startReceiveLoop() {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            val stream = inputStream ?: return@launch
            var state = 0 // 0: WAIT_AA, 1: WAIT_55, 2: WAIT_TYPE, 3: WAIT_LEN, 4: READ_PAYLOAD
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
                        0 -> {
                            if (b == 0xAA) state = 1
                        }
                        1 -> {
                            if (b == 0x55) {
                                state = 2
                            } else if (b != 0xAA) {
                                state = 0
                            }
                        }
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
                if (isActive) {
                    Log.d(TAG, "SPP Receive loop ended: ${e.message}")
                }
            }
        }
    }

    private fun handleReceivedPacket(type: Byte, payload: ByteArray) {
        if (type == 0x04.toByte() && payload.isNotEmpty()) {
            val cmd = payload[0]
            Log.d(TAG, "Media Control Command Received: 0x%02X".format(cmd))
            onCommandReceived?.invoke(cmd)
        }
    }

    // ★ rgb565Color (アクセント色) と rgb565BgColor (文字裏背景色) を送信
    fun sendMediaPacket(
        title: String,
        artist: String,
        album: String,
        jpegBytes: ByteArray?,
        rgb565Color: Int = 0x067F,
        rgb565BgColor: Int = 0x0000
    ) {
        scope.launch {
            if (_connectionStatus.value != ConnectionStatus.CONNECTED || outputStream == null) return@launch

            sendMutex.withLock {
                try {
                    val out = outputStream ?: return@withLock

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

                    delay(180)

                    if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                        val imgPacket = buildPacket(0x02.toByte(), jpegBytes)
                        var offset = 0
                        val chunkSize = 128
                        while (offset < imgPacket.size) {
                            val len = minOf(chunkSize, imgPacket.size - offset)
                            out.write(imgPacket, offset, len)
                            out.flush()
                            offset += len
                            delay(12)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send media packet", e)
                    _connectionStatus.value = ConnectionStatus.ERROR
                    disconnect()
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