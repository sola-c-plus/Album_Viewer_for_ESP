package com.example.album_viewer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
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
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val gson = Gson()
    private val sendMutex = Mutex()

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
                _connectionStatus.value = ConnectionStatus.CONNECTED
                Log.d(TAG, "Bluetooth connected: ${device.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth connection failed", e)
                _connectionStatus.value = ConnectionStatus.ERROR
                disconnect()
            }
        }
    }

    fun disconnect() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            // ignore
        } finally {
            socket = null
            outputStream = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _connectedDeviceName.value = null
        }
    }

    fun sendMediaPacket(title: String, artist: String, album: String, jpegBytes: ByteArray?) {
        scope.launch {
            if (_connectionStatus.value != ConnectionStatus.CONNECTED || outputStream == null) return@launch

            sendMutex.withLock {
                try {
                    val metadataMap = mapOf("title" to title, "artist" to artist, "album" to album)
                    val jsonBytes = gson.toJson(metadataMap).toByteArray(Charsets.UTF_8)
                    sendRaw(buildPacket(0x01.toByte(), jsonBytes))

                    delay(60)

                    if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                        sendRaw(buildPacket(0x02.toByte(), jpegBytes))
                        Log.d(TAG, "Sent Media: $title (${jpegBytes.size} bytes)")
                    }
                } catch (e: Exception) {
                    _connectionStatus.value = ConnectionStatus.ERROR
                    disconnect()
                }
            }
        }
    }

    /**
     * 時・分・秒をESP32へ同期送信 (Type: 0x03)
     */
    fun sendTimeSyncPacket(hour: Int, minute: Int, second: Int) {
        scope.launch {
            if (_connectionStatus.value != ConnectionStatus.CONNECTED || outputStream == null) return@launch

            sendMutex.withLock {
                try {
                    val timeMap = mapOf("h" to hour, "m" to minute, "s" to second)
                    val jsonBytes = gson.toJson(timeMap).toByteArray(Charsets.UTF_8)
                    sendRaw(buildPacket(0x03.toByte(), jsonBytes))
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    private suspend fun sendRaw(packet: ByteArray) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IOException("Output stream is null")
        out.write(packet)
        out.flush()
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