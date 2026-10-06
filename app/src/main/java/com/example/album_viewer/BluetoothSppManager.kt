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
            Log.e(TAG, "Error closing socket", e)
        } finally {
            socket = null
            outputStream = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _connectedDeviceName.value = null
        }
    }

    fun sendMediaPacket(title: String, artist: String, album: String, jpegBytes: ByteArray?) {
        scope.launch {
            if (_connectionStatus.value != ConnectionStatus.CONNECTED || outputStream == null) {
                return@launch
            }

            try {
                // 1. JSON メタデータ送信
                val metadataMap = mapOf(
                    "title" to title,
                    "artist" to artist,
                    "album" to album
                )
                val jsonStr = gson.toJson(metadataMap)
                val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
                val metaPacket = buildPacket(0x01.toByte(), jsonBytes)
                sendChunked(metaPacket)

                delay(80)

                // 2. JPEG 画像送信 (512B チャンク送信)
                if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                    val imgPacket = buildPacket(0x02.toByte(), jpegBytes)
                    sendChunked(imgPacket)
                    Log.d(TAG, "JPEG Sent completely (${jpegBytes.size} bytes)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send packet", e)
                _connectionStatus.value = ConnectionStatus.ERROR
                disconnect()
            }
        }
    }

    /**
     * 512バイトずつ小分けにして安全にストリーム送信する
     */
    private suspend fun sendChunked(packet: ByteArray, chunkSize: Int = 512) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IOException("Output stream is null")
        var offset = 0
        while (offset < packet.size) {
            val len = minOf(chunkSize, packet.size - offset)
            out.write(packet, offset, len)
            out.flush()
            offset += len
            delay(3) // ESP32の処理猶予（バッファあふれ防止）
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