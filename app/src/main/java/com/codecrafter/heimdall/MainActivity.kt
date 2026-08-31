package com.codecrafter.heimdall

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private val Navy = Color(0xFF102A4C)
private val Blue = Color(0xFF3565C5)
private val SoftBlue = Color(0xFFF3F7FF)
private val Muted = Color(0xFF667085)
private val Green = Color(0xFF16875B)
private val Amber = Color(0xFFB76E00)

private const val BLUETOOTH_SCAN_WINDOW_MS = 15_000L
private const val SCAN_CYCLE_MS = 60_000L
private const val VISIBLE_FRESHNESS_MS = 75_000L

private const val BLE_RESULT_MAX_AGE_MS = 20_000L
private const val WIFI_SCAN_TIMEOUT_MS = 10_000L
private const val WIFI_RESULT_MAX_AGE_MS = 20_000L
private const val WIFI_REQUEST_TOLERANCE_MS = 2_500L
private const val WIFI_P2P_DISCOVERY_WINDOW_MS = 20_000L
private const val CELL_SCAN_TIMEOUT_MS = 7_000L
private const val CELL_RESULT_MAX_AGE_MS = 30_000L

private enum class RadioType { BLUETOOTH, WIFI, WIFI_DIRECT, CELLULAR, RF }
private enum class Proximity { CLOSE, MEDIUM, FAR, UNKNOWN }

private data class SeenDevice(
    val id: String,
    val name: String,
    val type: RadioType,
    val signalDbm: Int?,
    val proximity: Proximity,
    val address: String?,
    val detail: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenCount: Int,
    val seenCycles: Int,
    val lastCycleId: Long,
    val sourceAgeMs: Long? = null
)

private data class RfReceiverState(
    val connected: Boolean = false,
    val name: String = "No SDR",
    val detail: String = "RF dormant · connect a supported USB SDR receiver"
)

private data class PassiveWifiState(
    val connected: Boolean = false,
    val name: String = "No passive WiFi sensor",
    val detail: String = "Phone WiFi sees access points, not arbitrary clients behind a router"
)

class MainActivity : ComponentActivity() {
    private val devices = ConcurrentHashMap<String, SeenDevice>()

    private var deviceSnapshot by mutableStateOf<List<SeenDevice>>(emptyList())
    private var scanning by mutableStateOf(false)
    private var statusText by mutableStateOf("Preparing scanner…")
    private var scanWarnings by mutableStateOf<List<String>>(emptyList())
    private var rfState by mutableStateOf(RfReceiverState())
    private var passiveWifiState by mutableStateOf(PassiveWifiState())

    private var scanJob: Job? = null
    private var manualScanJob: Job? = null
    private var scanCycleRunning = false
    private var currentCycleId = 0L

    private var classicReceiverRegistered = false
    private var wifiP2pReceiverRegistered = false
    private var wifiScanReceiverRegistered = false

    private var bluetoothActiveCycleId = 0L
    private var bluetoothWindowStartedElapsed = 0L
    private var bluetoothWindowStartedNanos = 0L

    private var wifiP2pActiveCycleId = 0L
    private var wifiP2pStartedElapsed = 0L

    private var wifiScanCompletion: CompletableDeferred<Boolean>? = null

    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val telephonyManager by lazy { getSystemService(TelephonyManager::class.java) }
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val wifiP2pManager by lazy {
        getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private var wifiP2pChannel: WifiP2pManager.Channel? = null

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleBle(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleBle)
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                addWarning("Bluetooth LE scan failed. No old Bluetooth results were substituted.")
                statusText = "Bluetooth LE unavailable ($errorCode)"
            }
        }
    }

    private val classicBluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            when (safeIntent.action) {
                BluetoothDevice.ACTION_FOUND -> handleClassicBluetoothFound(safeIntent)
                BluetoothDevice.ACTION_NAME_CHANGED -> handleBluetoothNameChanged(safeIntent)
            }
        }
    }

    private val wifiP2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) return

            val cycleId = wifiP2pActiveCycleId
            val age = SystemClock.elapsedRealtime() - wifiP2pStartedElapsed
            if (cycleId == 0L || cycleId != currentCycleId || age !in 0..WIFI_P2P_DISCOVERY_WINDOW_MS) {
                return
            }

            requestWifiP2pPeers(cycleId)
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            wifiScanCompletion?.let { completion ->
                if (!completion.isCompleted) completion.complete(updated)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiP2pChannel = if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            runCatching { wifiP2pManager.initialize(this, mainLooper, null) }.getOrNull()
        } else {
            null
        }

        registerClassicBluetoothReceiver()
        registerWifiP2pReceiver()
        registerWifiScanReceiver()
        refreshExternalSensorStates()

        setContent {
            HeimdallTheme {
                PermissionGate(
                    onPermissionsReady = { startScanning() },
                    content = {
                        HeimdallScreen(
                            devices = deviceSnapshot,
                            scanning = scanning,
                            status = statusText,
                            warnings = scanWarnings,
                            rfState = rfState,
                            passiveWifiState = passiveWifiState,
                            onRefresh = { forceRefresh() },
                            onToggle = { if (scanning) pauseScanning() else startScanning() }
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshExternalSensorStates()
    }

    override fun onDestroy() {
        pauseScanning()
        manualScanJob?.cancel()
        wifiScanCompletion?.cancel()
        unregisterClassicBluetoothReceiver()
        unregisterWifiP2pReceiver()
        unregisterWifiScanReceiver()
        super.onDestroy()
    }

    private fun registerClassicBluetoothReceiver() {
        if (classicReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            classicBluetoothReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        classicReceiverRegistered = true
    }

    private fun unregisterClassicBluetoothReceiver() {
        if (!classicReceiverRegistered) return
        runCatching { unregisterReceiver(classicBluetoothReceiver) }
        classicReceiverRegistered = false
    }

    private fun registerWifiP2pReceiver() {
        if (wifiP2pReceiverRegistered || wifiP2pChannel == null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            this,
            wifiP2pReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        wifiP2pReceiverRegistered = true
    }

    private fun unregisterWifiP2pReceiver() {
        if (!wifiP2pReceiverRegistered) return
        runCatching { unregisterReceiver(wifiP2pReceiver) }
        wifiP2pReceiverRegistered = false
    }

    private fun registerWifiScanReceiver() {
        if (wifiScanReceiverRegistered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        ContextCompat.registerReceiver(
            this,
            wifiScanReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        wifiScanReceiverRegistered = true
    }

    private fun unregisterWifiScanReceiver() {
        if (!wifiScanReceiverRegistered) return
        runCatching { unregisterReceiver(wifiScanReceiver) }
        wifiScanReceiverRegistered = false
    }

    @Composable
    private fun PermissionGate(
        onPermissionsReady: () -> Unit,
        content: @Composable () -> Unit
    ) {
        var permissionPromptCompleted by remember { mutableStateOf(false) }

        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            permissionPromptCompleted = true
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty() || permissionPromptCompleted) {
            LaunchedEffect(permissionPromptCompleted, missing.size) {
                onPermissionsReady()
            }
            content()
        } else {
            PermissionScreen { launcher.launch(missing.toTypedArray()) }
        }
    }

    private fun startScanning() {
        if (scanning) return

        scanning = true
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            while (isActive && scanning) {
                val startedAt = SystemClock.elapsedRealtime()
                runScanCycle(manual = false)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val wait = max(1_000L, SCAN_CYCLE_MS - elapsed)
                delay(wait)
            }
        }
    }

    private suspend fun runScanCycle(manual: Boolean) {
        if (scanCycleRunning) {
            if (manual) statusText = "A fresh scan is already in progress"
            return
        }

        scanCycleRunning = true
        val cycleStartedElapsed = SystemClock.elapsedRealtime()
        val cycleId = ++currentCycleId
        scanWarnings = emptyList()

        publishSnapshot()

        try {
            refreshExternalSensorStates()

            val bluetoothStarted = startBluetoothWindow(cycleId)
            startWifiP2pDiscovery(cycleId)

            statusText = when {
                bluetoothStarted && rfState.connected -> "Scanning fresh nearby signals · SDR connected"
                bluetoothStarted -> "Scanning fresh nearby signals…"
                rfState.connected -> "Scanning available fresh signals · SDR connected"
                else -> "Scanning available fresh signals · Bluetooth unavailable"
            }

            if (!bluetoothStarted) {
                addWarning("Bluetooth did not start. Previous Bluetooth observations are not shown as current.")
            }

            val wifiFresh = refreshWifi(cycleId)
            if (!wifiFresh) {
                addWarning("WiFi did not produce a fresh scan. Cached WiFi results were discarded.")
            }

            val cellularFresh = refreshCellular(cycleId)
            if (!cellularFresh && packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                addWarning("Cellular data was not fresh enough. Cached modem results were discarded.")
            }

            publishSnapshot()

            val elapsed = SystemClock.elapsedRealtime() - cycleStartedElapsed
            if (elapsed < BLUETOOTH_SCAN_WINDOW_MS) {
                delay(BLUETOOTH_SCAN_WINDOW_MS - elapsed)
            }
        } finally {
            stopBluetooth()
            stopWifiP2pDiscovery()
            publishSnapshot()
            scanCycleRunning = false

            if (scanning) {
                statusText = if (rfState.connected) {
                    "Fresh scan complete · RF receiver ready"
                } else {
                    "Fresh scan complete"
                }
            } else if (manual) {
                statusText = "Refresh complete"
            }
        }
    }

    private fun forceRefresh() {
        if (scanCycleRunning) {
            statusText = "A fresh scan is already in progress"
            return
        }

        manualScanJob?.cancel()
        manualScanJob = lifecycleScope.launch {
            statusText = "Refreshing with fresh results only…"
            runScanCycle(manual = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pauseScanning() {
        scanning = false
        scanJob?.cancel()
        scanJob = null
        stopBluetooth()
        stopWifiP2pDiscovery()
        statusText = "Paused"
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothWindow(cycleId: Long): Boolean {
        val adapter: BluetoothAdapter = bluetoothManager.adapter ?: return false
        if (!adapter.isEnabled) return false

        if (adapter.isDiscovering) {
            runCatching { adapter.cancelDiscovery() }
        }

        bluetoothActiveCycleId = cycleId
        bluetoothWindowStartedElapsed = SystemClock.elapsedRealtime()
        bluetoothWindowStartedNanos = SystemClock.elapsedRealtimeNanos()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val bleStarted = runCatching {
            val scanner = adapter.bluetoothLeScanner ?: return@runCatching false
            scanner.startScan(null, settings, bleCallback)
            true
        }.getOrDefault(false)

        val classicStarted = runCatching { adapter.startDiscovery() }.getOrDefault(false)

        if (!bleStarted && !classicStarted) {
            bluetoothActiveCycleId = 0L
        }

        return bleStarted || classicStarted
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetooth() {
        runCatching {
            bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(bleCallback)
        }
        runCatching {
            bluetoothManager.adapter?.let { adapter ->
                if (adapter.isDiscovering) adapter.cancelDiscovery()
            }
        }
        bluetoothActiveCycleId = 0L
    }

    @SuppressLint("MissingPermission")
    private fun handleBle(result: ScanResult) {
        val cycleId = bluetoothActiveCycleId
        if (cycleId == 0L || cycleId != currentCycleId) return

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val resultNanos = result.timestampNanos
        val ageMs = ((nowNanos - resultNanos) / 1_000_000L).coerceAtLeast(0L)

        if (resultNanos < bluetoothWindowStartedNanos || ageMs > BLE_RESULT_MAX_AGE_MS) return

        val now = System.currentTimeMillis()
        val address = runCatching { result.device.address }.getOrNull()
            ?.takeIf(::isUsableRadioAddress)
            ?: return

        val advertisedName = result.scanRecord?.deviceName
        val deviceName = runCatching { result.device.name }.getOrNull()
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { result.device.alias }.getOrNull()
        } else {
            null
        }

        val name = resolveBluetoothName(address, advertisedName, deviceName, alias)
        val manufacturerIds = result.scanRecord?.manufacturerSpecificData?.let { data ->
            (0 until data.size()).joinToString { data.keyAt(it).toString() }
        }.orEmpty()
        val services = result.scanRecord?.serviceUuids
            ?.joinToString { it.uuid.toString().take(8) }
            .orEmpty()

        val detail = buildString {
            append("Fresh Bluetooth LE observation · RSSI ${result.rssi} dBm")
            if (manufacturerIds.isNotBlank()) append(" · Manufacturer ID $manufacturerIds")
            if (services.isNotBlank()) append(" · Services $services")
            append(" · Advertised identity is not verified")
        }

        upsert(
            SeenDevice(
                id = "bt:$address",
                name = name,
                type = RadioType.BLUETOOTH,
                signalDbm = result.rssi,
                proximity = proximityFromSignal(RadioType.BLUETOOTH, result.rssi),
                address = address,
                detail = detail,
                firstSeen = now,
                lastSeen = now,
                seenCount = 1,
                seenCycles = 1,
                lastCycleId = cycleId,
                sourceAgeMs = ageMs
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun handleClassicBluetoothFound(intent: Intent) {
        val cycleId = bluetoothActiveCycleId
        val elapsed = SystemClock.elapsedRealtime() - bluetoothWindowStartedElapsed
        if (cycleId == 0L || cycleId != currentCycleId || elapsed !in 0..BLE_RESULT_MAX_AGE_MS) return

        val device = bluetoothDeviceFromIntent(intent) ?: return
        val address = runCatching { device.address }.getOrNull()
            ?.takeIf(::isUsableRadioAddress)
            ?: return

        val rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
        if (rssiShort == Short.MIN_VALUE) return

        val advertisedName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
        val deviceName = runCatching { device.name }.getOrNull()
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { device.alias }.getOrNull()
        } else {
            null
        }

        val rssi = rssiShort.toInt()
        val now = System.currentTimeMillis()

        upsert(
            SeenDevice(
                id = "bt:$address",
                name = resolveBluetoothName(address, advertisedName, deviceName, alias),
                type = RadioType.BLUETOOTH,
                signalDbm = rssi,
                proximity = proximityFromSignal(RadioType.BLUETOOTH, rssi),
                address = address,
                detail = "Fresh Classic Bluetooth discovery · RSSI $rssi dBm · Advertised identity is not verified",
                firstSeen = now,
                lastSeen = now,
                seenCount = 1,
                seenCycles = 1,
                lastCycleId = cycleId,
                sourceAgeMs = 0L
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothNameChanged(intent: Intent) {
        val device = bluetoothDeviceFromIntent(intent) ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        val changedName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
            ?: runCatching { device.name }.getOrNull()
            ?: return
        if (changedName.isBlank()) return

        devices.computeIfPresent("bt:$address") { _, old -> old.copy(name = changedName.trim()) }
        runOnUiThread { publishSnapshot() }
    }

    @SuppressLint("MissingPermission")
    private fun resolveBluetoothName(
        address: String,
        advertisedName: String?,
        deviceName: String?,
        alias: String?
    ): String {
        val bonded = runCatching {
            bluetoothManager.adapter?.bondedDevices?.firstOrNull {
                runCatching { it.address.equals(address, ignoreCase = true) }.getOrDefault(false)
            }
        }.getOrNull()

        val bondedName = bonded?.let { runCatching { it.name }.getOrNull() }
        val bondedAlias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bonded?.let { runCatching { it.alias }.getOrNull() }
        } else {
            null
        }

        return listOf(advertisedName, deviceName, alias, bondedName, bondedAlias)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?: "Unknown Bluetooth device"
    }

    @Suppress("DEPRECATION")
    private fun bluetoothDeviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWifiP2pDiscovery(cycleId: Long) {
        val channel = wifiP2pChannel ?: return
        if (!hasWifiP2pPermission()) return

        wifiP2pActiveCycleId = cycleId
        wifiP2pStartedElapsed = SystemClock.elapsedRealtime()

        runCatching {
            wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit

                override fun onFailure(reason: Int) {
                    if (wifiP2pActiveCycleId == cycleId) {
                        wifiP2pActiveCycleId = 0L
                        addWarning("WiFi Direct discovery failed ($reason). Old peer lists were not shown.")
                    }
                }
            })
        }.onFailure {
            wifiP2pActiveCycleId = 0L
            addWarning("WiFi Direct discovery could not start. Old peer lists were not shown.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestWifiP2pPeers(cycleId: Long) {
        val channel = wifiP2pChannel ?: return
        if (!hasWifiP2pPermission()) return
        if (cycleId != wifiP2pActiveCycleId || cycleId != currentCycleId) return

        runCatching {
            wifiP2pManager.requestPeers(channel) { peerList ->
                val age = SystemClock.elapsedRealtime() - wifiP2pStartedElapsed
                if (cycleId != currentCycleId || age !in 0..WIFI_P2P_DISCOVERY_WINDOW_MS) {
                    return@requestPeers
                }

                peerList.deviceList.forEach { handleWifiP2pDevice(it, cycleId) }
                wifiP2pActiveCycleId = 0L
                publishSnapshot()
            }
        }.onFailure {
            wifiP2pActiveCycleId = 0L
            addWarning("WiFi Direct peer read failed. Cached peers were not substituted.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopWifiP2pDiscovery() {
        val channel = wifiP2pChannel ?: return
        if (!hasWifiP2pPermission()) return

        runCatching {
            wifiP2pManager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        }
        wifiP2pActiveCycleId = 0L
    }

    private fun hasWifiP2pPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handleWifiP2pDevice(device: WifiP2pDevice, cycleId: Long) {
        if (cycleId != currentCycleId) return

        val address = device.deviceAddress
            ?.takeIf { it.isNotBlank() }
            ?.takeIf(::isUsableRadioAddress)
            ?: return

        val now = System.currentTimeMillis()
        val name = device.deviceName
            ?.takeIf { it.isNotBlank() }
            ?.trim()
            ?: "Unknown WiFi Direct device"

        val status = when (device.status) {
            WifiP2pDevice.CONNECTED -> "connected"
            WifiP2pDevice.INVITED -> "invited"
            WifiP2pDevice.FAILED -> "failed"
            WifiP2pDevice.AVAILABLE -> "available"
            WifiP2pDevice.UNAVAILABLE -> "unavailable"
            else -> "unknown"
        }
        val type = device.primaryDeviceType?.takeIf { it.isNotBlank() } ?: "unknown type"

        upsert(
            SeenDevice(
                id = "p2p:$address",
                name = name,
                type = RadioType.WIFI_DIRECT,
                signalDbm = null,
                proximity = Proximity.UNKNOWN,
                address = address,
                detail = "Fresh WiFi Direct discovery peer · $status · $type · Device name is advertised and not identity verified · Android exposes no peer RSSI here",
                firstSeen = now,
                lastSeen = now,
                seenCount = 1,
                seenCycles = 1,
                lastCycleId = cycleId,
                sourceAgeMs = SystemClock.elapsedRealtime() - wifiP2pStartedElapsed
            )
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun refreshWifi(cycleId: Long): Boolean {
        val completion = CompletableDeferred<Boolean>()
        wifiScanCompletion?.cancel()
        wifiScanCompletion = completion

        val requestedAtElapsed = SystemClock.elapsedRealtime()
        val started = runCatching { wifiManager.startScan() }.getOrDefault(false)
        if (!started) {
            if (wifiScanCompletion === completion) wifiScanCompletion = null
            completion.cancel()
            return false
        }

        val updated = withTimeoutOrNull(WIFI_SCAN_TIMEOUT_MS) {
            completion.await()
        } ?: false

        if (wifiScanCompletion === completion) wifiScanCompletion = null
        if (!updated || cycleId != currentCycleId) return false

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val results: List<WifiScanResult> = runCatching {
            wifiManager.scanResults
        }.getOrDefault(emptyList())

        var accepted = 0

        results.forEach { result ->
            val resultElapsed = result.timestamp / 1_000L
            val sourceAgeMs = nowElapsed - resultElapsed

            if (sourceAgeMs !in 0..WIFI_RESULT_MAX_AGE_MS) return@forEach
            if (resultElapsed + WIFI_REQUEST_TOLERANCE_MS < requestedAtElapsed) return@forEach

            val bssid = result.BSSID
                ?.takeIf { it.isNotBlank() }
                ?.takeIf(::isUsableRadioAddress)
                ?: return@forEach

            val rawName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.wifiSsid?.toString()?.trim('"')
            } else {
                result.SSID
            }

            val name = rawName
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                ?.trim()
                ?: "WiFi network · SSID hidden or unavailable"

            val frequency = result.frequency
            val band = when {
                frequency >= 5925 -> "6 GHz"
                frequency >= 4900 -> "5 GHz"
                frequency > 0 -> "2.4 GHz"
                else -> "Unknown band"
            }
            val security = securityLabel(result.capabilities)

            upsert(
                SeenDevice(
                    id = "wifi:$bssid",
                    name = name,
                    type = RadioType.WIFI,
                    signalDbm = result.level,
                    proximity = proximityFromSignal(RadioType.WIFI, result.level),
                    address = bssid,
                    detail = "Fresh WiFi scan · RSSI ${result.level} dBm · $band · $security · ${frequency} MHz · SSID and BSSID do not prove device identity",
                    firstSeen = nowWall,
                    lastSeen = nowWall,
                    seenCount = 1,
                    seenCycles = 1,
                    lastCycleId = cycleId,
                    sourceAgeMs = sourceAgeMs
                )
            )
            accepted++
        }

        publishSnapshot()
        return accepted > 0 || results.isEmpty()
    }

    @SuppressLint("MissingPermission")
    private suspend fun refreshCellular(cycleId: Long): Boolean {
        val hasLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return false

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return true

        val cells = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val completion = CompletableDeferred<List<CellInfo>?>()

            runCatching {
                telephonyManager.requestCellInfoUpdate(
                    mainExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (!completion.isCompleted) completion.complete(cellInfo)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            if (!completion.isCompleted) completion.complete(null)
                        }
                    }
                )
            }.onFailure {
                if (!completion.isCompleted) completion.complete(null)
            }

            withTimeoutOrNull(CELL_SCAN_TIMEOUT_MS) { completion.await() }
        } else {
            runCatching { telephonyManager.allCellInfo ?: emptyList() }.getOrNull()
        } ?: return false

        if (cycleId != currentCycleId) return false
        return handleCells(cells, cycleId)
    }

    @Suppress("DEPRECATION")
    private fun handleCells(cells: List<CellInfo>, cycleId: Long): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        var accepted = 0

        cells.forEachIndexed { index, cell ->
            val timestampMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cell.timestampMillis
            } else {
                cell.timeStamp / 1_000_000L
            }
            val sourceAgeMs = nowElapsed - timestampMs

            if (sourceAgeMs !in 0..CELL_RESULT_MAX_AGE_MS) return@forEachIndexed

            val (technology, dbm, identity) = cellSummary(cell)
            val current = cell.isRegistered

            upsert(
                SeenDevice(
                    id = "cell:$technology:$identity",
                    name = if (current) "$technology current network cell" else "$technology nearby network cell",
                    type = RadioType.CELLULAR,
                    signalDbm = dbm,
                    proximity = Proximity.UNKNOWN,
                    address = null,
                    detail = if (current) {
                        "Fresh modem observation · Signal ${dbm?.let { "$it dBm" } ?: "unknown"} · Cell $identity · Your phone is registered on this network cell"
                    } else {
                        "Fresh modem observation · Signal ${dbm?.let { "$it dBm" } ?: "unknown"} · Cell $identity · Visible as a possible handover cell"
                    },
                    firstSeen = nowWall + index,
                    lastSeen = nowWall,
                    seenCount = 1,
                    seenCycles = 1,
                    lastCycleId = cycleId,
                    sourceAgeMs = sourceAgeMs
                )
            )
            accepted++
        }

        publishSnapshot()
        return accepted > 0 || cells.isEmpty()
    }

    private fun cellSummary(cell: CellInfo): Triple<String, Int?, String> = when (cell) {
        is CellInfoLte -> Triple("LTE", cell.cellSignalStrength.dbm, cell.cellIdentity.ci.toString())
        is CellInfoWcdma -> Triple("3G", cell.cellSignalStrength.dbm, cell.cellIdentity.cid.toString())
        is CellInfoGsm -> Triple("GSM", cell.cellSignalStrength.dbm, cell.cellIdentity.cid.toString())
        is CellInfoCdma -> Triple("CDMA", cell.cellSignalStrength.dbm, cell.cellIdentity.basestationId.toString())
        is CellInfoNr -> Triple("5G", cell.cellSignalStrength.dbm, cell.cellIdentity.toString().hashCode().toString())
        else -> Triple("Cellular", null, cell.hashCode().toString())
    }

    private fun refreshExternalSensorStates() {
        refreshRfReceiverState()
        refreshPassiveWifiReceiverState()
    }

    private fun refreshRfReceiverState() {
        val matched = usbManager.deviceList.values.firstOrNull(::isSupportedSdr)
        rfState = if (matched == null) {
            RfReceiverState()
        } else {
            val product = matched.productName?.takeIf { it.isNotBlank() }
                ?: matched.manufacturerName?.takeIf { it.isNotBlank() }
                ?: "USB SDR receiver"

            RfReceiverState(
                connected = true,
                name = product,
                detail = "SDR detected over USB · RF stays observation only until a capture backend returns timestamped signal data"
            )
        }
    }

    private fun isSupportedSdr(device: UsbDevice): Boolean {
        val vendor = device.vendorId
        val product = device.productId

        if (vendor == 0x0BDA && (product == 0x2832 || product == 0x2838)) return true

        val name = listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        if ("hackrf" in name) return true

        return "rtl-sdr" in name || "rtlsdr" in name || "nesdr" in name
    }

    private fun refreshPassiveWifiReceiverState() {
        val matched = usbManager.deviceList.values.firstOrNull(::isSupportedPassiveWifiAdapter)
        passiveWifiState = if (matched == null) {
            PassiveWifiState()
        } else {
            val product = matched.productName?.takeIf { it.isNotBlank() }
                ?: matched.manufacturerName?.takeIf { it.isNotBlank() }
                ?: "USB WiFi monitor adapter"

            PassiveWifiState(
                connected = true,
                name = product,
                detail = "USB WiFi sensor detected · passive client frame capture requires the HEIMDALL Linux monitor bridge"
            )
        }
    }

    private fun isSupportedPassiveWifiAdapter(device: UsbDevice): Boolean {
        if (device.vendorId == 0x0E8D && device.productId == 0x7612) return true

        val name = listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        return listOf("awus036acm", "awus036axm", "awus036axml", "mt7612", "mt7921")
            .any { it in name }
    }

    private fun upsert(newValue: SeenDevice) {
        devices.compute(newValue.id) { _, old ->
            if (old == null) {
                newValue
            } else {
                val preferredName = when {
                    !isGenericName(newValue.name) -> newValue.name
                    !isGenericName(old.name) -> old.name
                    else -> newValue.name
                }

                val newCycle = old.lastCycleId != newValue.lastCycleId

                newValue.copy(
                    name = preferredName,
                    firstSeen = old.firstSeen,
                    seenCount = old.seenCount + 1,
                    seenCycles = old.seenCycles + if (newCycle) 1 else 0
                )
            }
        }

        runOnUiThread { publishSnapshot() }
    }

    private fun isGenericName(name: String): Boolean =
        name.isBlank() ||
            name == "Unknown Bluetooth device" ||
            name == "Unknown WiFi Direct device" ||
            name.startsWith("WiFi network · SSID hidden")

    private fun publishSnapshot() {
        val now = System.currentTimeMillis()
        val cycleId = currentCycleId

        deviceSnapshot = devices.values
            .asSequence()
            .filter { it.lastCycleId == cycleId }
            .filter { now - it.lastSeen < VISIBLE_FRESHNESS_MS }
            .sortedWith(
                compareBy<SeenDevice> { proximityRank(it) }
                    .thenByDescending { it.signalDbm ?: Int.MIN_VALUE }
                    .thenByDescending { it.lastSeen }
            )
            .toList()
    }

    private fun addWarning(message: String) {
        runOnUiThread {
            if (message !in scanWarnings) {
                scanWarnings = (scanWarnings + message).takeLast(4)
            }
        }
    }

    private fun isUsableRadioAddress(address: String): Boolean {
        val normalized = address.trim().uppercase(Locale.ROOT)
        if (normalized.isBlank()) return false
        if (normalized == "02:00:00:00:00:00") return false
        if (normalized == "00:00:00:00:00:00") return false
        return true
    }

    private fun securityLabel(capabilities: String): String = when {
        "WPA3" in capabilities || "SAE" in capabilities -> "WPA3"
        "WPA2" in capabilities || "RSN" in capabilities -> "WPA2"
        "WPA" in capabilities -> "WPA"
        "WEP" in capabilities -> "WEP"
        else -> "Open"
    }
}

@Composable
private fun HeimdallTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Navy,
            secondary = Blue,
            surface = Color.White,
            background = Color.White
        ),
        content = {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                content()
            }
        }
    )
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeimdallLogo(88.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            "HEIMDALL",
            color = Navy,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Allow Nearby devices and Location so HEIMDALL can scan Bluetooth, WiFi access points, WiFi Direct peers and cellular signals. HEIMDALL discards stale scan data instead of presenting it as current.",
            color = Muted,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGrant,
            colors = ButtonDefaults.buttonColors(containerColor = Navy)
        ) {
            Text("Allow scanning")
        }
    }
}

@Composable
private fun HeimdallScreen(
    devices: List<SeenDevice>,
    scanning: Boolean,
    status: String,
    warnings: List<String>,
    rfState: RfReceiverState,
    passiveWifiState: PassiveWifiState,
    onRefresh: () -> Unit,
    onToggle: () -> Unit
) {
    val bluetoothCount = devices.count { it.type == RadioType.BLUETOOTH && isRecent(it) }
    val wifiCount = devices.count {
        (it.type == RadioType.WIFI || it.type == RadioType.WIFI_DIRECT) && isRecent(it)
    }
    val cellularCount = devices.count { it.type == RadioType.CELLULAR && isRecent(it) }
    val rfCount = devices.count { it.type == RadioType.RF && isRecent(it) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeimdallLogo(54.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "HEIMDALL",
                        color = Navy,
                        fontWeight = FontWeight.Bold,
                        fontSize = 25.sp,
                        letterSpacing = 2.sp
                    )
                    Text(
                        status,
                        color = if (scanning) Green else Muted,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CounterCard("Bluetooth", bluetoothCount, RadioType.BLUETOOTH, Modifier.weight(1f))
                CounterCard("WiFi", wifiCount, RadioType.WIFI, Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CounterCard("Cellular", cellularCount, RadioType.CELLULAR, Modifier.weight(1f))
                CounterCard(
                    label = if (rfState.connected) "RF · SDR ready" else "RF · dormant",
                    count = rfCount,
                    type = RadioType.RF,
                    modifier = Modifier.weight(1f),
                    enabled = rfState.connected
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "${devices.count { isRecent(it) }} fresh signals observed in this scan",
                fontWeight = FontWeight.SemiBold,
                color = Navy
            )
            Text(
                "Close, Medium and Far are signal strength categories, not measured distance.",
                color = Muted,
                fontSize = 12.sp
            )
            Text(
                "Names and radio addresses can be advertised, randomized or spoofed. They are observations, not verified identities.",
                color = Muted,
                fontSize = 11.sp
            )

            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                warnings.forEach { warning ->
                    Text(warning, color = Amber, fontSize = 11.sp)
                }
            }

            Text(
                if (passiveWifiState.connected) {
                    "Passive WiFi sensor: ${passiveWifiState.name}"
                } else {
                    "Passive WiFi clients: external monitor sensor not connected"
                },
                color = if (passiveWifiState.connected) Green else Amber,
                fontSize = 11.sp
            )

            Text(
                if (rfState.connected) {
                    "RF receiver: ${rfState.name}"
                } else {
                    "RF receiver: not connected"
                },
                color = if (rfState.connected) Green else Muted,
                fontSize = 11.sp
            )
        }

        HorizontalDivider(color = Color(0xFFE8ECF2))

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (scanning) {
                        "No fresh nearby transmitters found in this scan"
                    } else {
                        "Scanning is paused"
                    },
                    color = Muted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(devices, key = { it.id }) { device ->
                    DeviceCard(device)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onRefresh,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Scan now", fontSize = 15.sp)
            }

            Button(
                onClick = onToggle,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy)
            ) {
                Icon(
                    if (scanning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(7.dp))
                Text(if (scanning) "Pause" else "Resume", fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun HeimdallLogo(size: androidx.compose.ui.unit.Dp) {
    Image(
        painter = painterResource(R.drawable.heimdall_logo),
        contentDescription = "HEIMDALL logo",
        modifier = Modifier.size(size)
    )
}

@Composable
private fun CounterCard(
    label: String,
    count: Int,
    type: RadioType,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) SoftBlue else Color(0xFFF7F7F8)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Icon(
                imageVector = when (type) {
                    RadioType.BLUETOOTH -> Icons.Default.Bluetooth
                    RadioType.WIFI, RadioType.WIFI_DIRECT -> Icons.Default.Wifi
                    RadioType.CELLULAR -> Icons.Default.CellTower
                    RadioType.RF -> Icons.Default.SettingsInputAntenna
                },
                contentDescription = null,
                tint = if (enabled) Blue else Muted
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (enabled) count.toString() else "—",
                color = if (enabled) Navy else Muted,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(label, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DeviceCard(device: SeenDevice) {
    var expanded by remember { mutableStateOf(false) }
    val label = proximityLabel(device)
    val labelColor = when (label) {
        "Close" -> Green
        "Medium" -> Amber
        "Strong" -> Green
        else -> Muted
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFBFD)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (device.type) {
                        RadioType.BLUETOOTH -> Icons.Default.Bluetooth
                        RadioType.WIFI, RadioType.WIFI_DIRECT -> Icons.Default.Wifi
                        RadioType.CELLULAR -> Icons.Default.CellTower
                        RadioType.RF -> Icons.Default.SettingsInputAntenna
                    },
                    contentDescription = null,
                    tint = Blue
                )

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        device.name,
                        color = Navy,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(typeLabel(device.type), color = Muted, fontSize = 12.sp)
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        label,
                        color = labelColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Text(
                        if (device.seenCycles >= 2) "Repeated" else "New",
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.height(7.dp))

            Text(
                "Last seen ${relativeTime(device.lastSeen)} · Seen in ${device.seenCycles} scan${if (device.seenCycles == 1) "" else "s"}",
                color = Muted,
                fontSize = 11.sp
            )

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Color(0xFFE5EAF1))
                Spacer(Modifier.height(10.dp))

                device.address?.let {
                    DetailLine(
                        when (device.type) {
                            RadioType.WIFI -> "BSSID"
                            RadioType.WIFI_DIRECT -> "P2P address"
                            else -> "Address"
                        },
                        it
                    )
                }

                device.signalDbm?.let {
                    DetailLine("Signal", "$it dBm")
                }

                device.sourceAgeMs?.let {
                    DetailLine("Source age", formatAge(it))
                }

                DetailLine("First seen", absoluteTime(device.firstSeen))
                DetailLine("Observations", device.seenCount.toString())
                DetailLine("Details", device.detail)

                Text(
                    "HEIMDALL reports radio observations. Device names, SSIDs, MAC style addresses and signal strength are not proof of physical identity or exact distance.",
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 7.dp)
                )

                if (device.type == RadioType.CELLULAR) {
                    Text(
                        "Cellular entries are network cells visible to the modem, not nearby phones. Signal level is not tower distance.",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (device.type == RadioType.WIFI_DIRECT) {
                    Text(
                        "WiFi Direct peer names are advertised by the peer and are not brand verified. Android does not expose peer ranging here.",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.width(82.dp)
        )
        Text(
            value,
            color = Navy,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun typeLabel(type: RadioType) = when (type) {
    RadioType.BLUETOOTH -> "Bluetooth Classic / BLE"
    RadioType.WIFI -> "WiFi access point"
    RadioType.WIFI_DIRECT -> "WiFi Direct peer"
    RadioType.CELLULAR -> "Cellular network cell"
    RadioType.RF -> "General RF"
}

private fun proximityFromSignal(type: RadioType, dbm: Int?): Proximity {
    if (dbm == null) return Proximity.UNKNOWN

    return when (type) {
        RadioType.BLUETOOTH -> when {
            dbm >= -60 -> Proximity.CLOSE
            dbm >= -78 -> Proximity.MEDIUM
            else -> Proximity.FAR
        }

        RadioType.WIFI -> when {
            dbm >= -55 -> Proximity.CLOSE
            dbm >= -72 -> Proximity.MEDIUM
            else -> Proximity.FAR
        }

        else -> Proximity.UNKNOWN
    }
}

private fun proximityLabel(device: SeenDevice): String = when (device.type) {
    RadioType.BLUETOOTH, RadioType.WIFI -> when (device.proximity) {
        Proximity.CLOSE -> "Close"
        Proximity.MEDIUM -> "Medium"
        Proximity.FAR -> "Far"
        Proximity.UNKNOWN -> "Observed"
    }

    RadioType.WIFI_DIRECT -> "Observed"
    RadioType.CELLULAR, RadioType.RF -> signalLabel(device.signalDbm)
}

private fun proximityRank(device: SeenDevice): Int = when (device.type) {
    RadioType.BLUETOOTH, RadioType.WIFI -> when (device.proximity) {
        Proximity.CLOSE -> 0
        Proximity.MEDIUM -> 1
        Proximity.FAR -> 2
        Proximity.UNKNOWN -> 3
    }

    else -> 4
}

private fun signalLabel(dbm: Int?): String = when {
    dbm == null -> "Observed"
    dbm >= -70 -> "Strong"
    dbm >= -90 -> "Medium"
    else -> "Weak"
}

private fun isRecent(device: SeenDevice): Boolean =
    System.currentTimeMillis() - device.lastSeen < VISIBLE_FRESHNESS_MS

private fun relativeTime(time: Long): String {
    val seconds = ((System.currentTimeMillis() - time) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> "now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}

private fun formatAge(ageMs: Long): String = when {
    ageMs < 1_000 -> "${ageMs} ms"
    ageMs < 60_000 -> "${ageMs / 1_000}s"
    else -> "${ageMs / 60_000}m"
}

private fun absoluteTime(time: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
