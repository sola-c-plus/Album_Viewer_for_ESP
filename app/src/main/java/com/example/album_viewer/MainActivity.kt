package com.example.album_viewer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF1DB954),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onPrimary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val connectionStatus by BluetoothSppManager.connectionStatus.collectAsState()
    val connectedDeviceName by BluetoothSppManager.connectedDeviceName.collectAsState()
    val currentTrack by MediaStateHolder.currentTrack.collectAsState()

    var hasBtPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    var isNotificationAccessGranted by remember {
        mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName))
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasBtPermission = perms.values.all { it }
    }

    val bluetoothAdapter = remember { BluetoothAdapter.getDefaultAdapter() }
    var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(hasBtPermission) {
        if (hasBtPermission && bluetoothAdapter != null) {
            pairedDevices = bluetoothAdapter.bondedDevices.toList()
            selectedDevice = pairedDevices.firstOrNull { it.name?.contains("ESP32", ignoreCase = true) == true }
                ?: pairedDevices.firstOrNull()
        }
    }

    DisposableEffect(Unit) {
        isNotificationAccessGranted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ESP32 Music Display",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (!isNotificationAccessGranted || !hasBtPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF332020)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("必要な権限を設定してください", fontWeight = FontWeight.Bold, color = Color(0xFFFF6B6B))
                    Spacer(modifier = Modifier.height(6.dp))
                    if (!isNotificationAccessGranted) {
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("1. 通知アクセス権限を許可")
                        }
                    }
                    if (!hasBtPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = {
                                btPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.BLUETOOTH_SCAN
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("2. Bluetooth接続権限を許可")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Bluetooth接続", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = !dropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedDevice?.name ?: (selectedDevice?.address ?: "デバイスを選択"),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("接続先デバイス") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        pairedDevices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text("${device.name ?: "Unknown"} (${device.address})") },
                                onClick = {
                                    selectedDevice = device
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val (statusText, statusColor) = when (connectionStatus) {
                        ConnectionStatus.CONNECTED -> "接続完了 (${connectedDeviceName})" to Color(0xFF4CAF50)
                        ConnectionStatus.CONNECTING -> "接続中..." to Color(0xFFFFC107)
                        ConnectionStatus.ERROR -> "接続エラー" to Color(0xFFF44336)
                        ConnectionStatus.DISCONNECTED -> "未接続" to Color.Gray
                    }
                    Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = statusText, color = statusColor, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            selectedDevice?.let { BluetoothSppManager.connect(it) }
                                ?: Toast.makeText(context, "デバイスを選択してください", Toast.LENGTH_SHORT).show()
                        },
                        enabled = connectionStatus != ConnectionStatus.CONNECTED &&
                                  connectionStatus != ConnectionStatus.CONNECTING &&
                                  selectedDevice != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("接続")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedButton(
                        onClick = { BluetoothSppManager.disconnect() },
                        enabled = connectionStatus == ConnectionStatus.CONNECTED ||
                                  connectionStatus == ConnectionStatus.CONNECTING,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("切断")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("再生中プレビュー (240x240 円形)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(2.dp, Color(0xFF444444), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentTrack.bitmap != null) {
                        Image(
                            bitmap = currentTrack.bitmap!!.asImageBitmap(),
                            contentDescription = "Album Art",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("No Art", color = Color.Gray, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = currentTrack.title.ifEmpty { "再生中の曲はありません" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = currentTrack.artist.ifEmpty { "-" },
                    fontSize = 14.sp,
                    color = Color.LightGray,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        BluetoothSppManager.sendMediaPacket(
                            currentTrack.title,
                            currentTrack.artist,
                            currentTrack.album,
                            currentTrack.jpegBytes
                        )
                    },
                    enabled = connectionStatus == ConnectionStatus.CONNECTED && currentTrack.title.isNotEmpty()
                ) {
                    Text("ESP32へ再送信")
                }
            }
        }
    }
}
