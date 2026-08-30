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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToInt

private val Navy = Color(0xFF102A4C)
private val Blue = Color(0xFF3565C5)
private val SoftBlue = Color(0xFFF3F7FF)
private val Muted = Color(0xFF667085)
private val Green = Color(0xFF16875B)
private val Amber = Color(0xFFB76E00)

private const val BLUETOOTH_SCAN_WINDOW_MS = 15_000L
private const val SCAN_CYCLE_MS = 60_000L
private const val MAX_VISIBLE_DISTANCE_METERS = 1.0
private const val VISIBLE_FRESHNESS_MS = 90_000L

private enum class RadioType { BLUETOOTH, WIFI, WIFI_DIRECT, CELLULAR, RF }

private data class SeenDevice(
    val id: String,
    val name: String,
    val type: RadioType,
    val signalDbm: Int?,
    val distanceMeters: Double?,
    val address: String?,
    val detail: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val seenCount: Int
)

private data class RfReceiverState(
    val connected: Boolean = false,
    val name: String = "No SDR",
    val detail: String = "RF dormant · connect a supported USB SDR receiver"
)

private data class PassiveWifiState(
    val connected: Boolean = false,
    val name: String = "No passive Wi‑Fi sensor",
    val detail: String = "Phone-only Wi‑Fi sees access points, not arbitrary clients behind a router"
)

class MainActivity : ComponentActivity() {
    private val devices = ConcurrentHashMap<String, SeenDevice>()
    private var deviceSnapshot by mutableStateOf<List<SeenDevice>>(emptyList())
    private var scanning by mutableStateOf(false)
    private var statusText by mutableStateOf("Preparing scanner…")
    private var rfState by mutableStateOf(RfReceiverState())
    private var passiveWifiState by mutableStateOf(PassiveWifiState())
    private var scanJob: Job? = null
    private var classicReceiverRegistered = false
    private var wifiP2pReceiverRegistered = false

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
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleBle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleBle)
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { statusText = "Bluetooth LE scan unavailable ($errorCode)" }
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
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestWifiP2pPeers()
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
        unregisterClassicBluetoothReceiver()
        unregisterWifiP2pReceiver()
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

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (scanning) return
        scanning = true
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            while (isActive && scanning) {
                performScanCycle()
                delay(SCAN_CYCLE_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun performScanCycle() {
        refreshExternalSensorStates()
        val bluetoothStarted = startBluetoothWindow()
        startWifiP2pDiscovery()

        statusText = when {
            bluetoothStarted && rfState.connected -> "Scanning nearby signals · SDR connected"
            bluetoothStarted -> "Scanning nearby signals…"
            rfState.connected -> "Scanning available signals · SDR connected"
            else -> "Scanning available signals · Bluetooth off"
        }

        refreshWifi()
        refreshCellular()
        publishSnapshot()

        delay(BLUETOOTH_SCAN_WINDOW_MS)
        stopBluetooth()
        publishSnapshot()

        if (scanning) {
            statusText = if (rfState.connected) {
                "Waiting for next scan · RF receiver ready"
            } else {
                "Waiting for next scan…"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun forceRefresh() {
        lifecycleScope.launch {
            statusText = "Refreshing now…"
            refreshExternalSensorStates()
            val bluetoothStarted = startBluetoothWindow()
            startWifiP2pDiscovery()
            refreshWifi()
            refreshCellular()
            publishSnapshot()
            delay(BLUETOOTH_SCAN_WINDOW_MS)
            if (bluetoothStarted) stopBluetooth()
            publishSnapshot()
            statusText = if (scanning) {
                if (rfState.connected) "Waiting for next scan · RF receiver ready" else "Waiting for next scan…"
            } else {
                "Refresh complete"
            }
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
    private fun startBluetoothWindow(): Boolean {
        val adapter: BluetoothAdapter = bluetoothManager.adapter ?: return false
        if (!adapter.isEnabled) return false

        if (adapter.isDiscovering) {
            runCatching { adapter.cancelDiscovery() }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val bleStarted = runCatching {
            val scanner = adapter.bluetoothLeScanner ?: return@runCatching false
            scanner.startScan(null, settings, bleCallback)
            true
        }.getOrDefault(false)

        // Classic discovery is essential for headphones, speakers, phones and
        // other devices that may not expose useful BLE advertisements.
        val classicStarted = runCatching { adapter.startDiscovery() }.getOrDefault(false)

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
    }

    @SuppressLint("MissingPermission")
    private fun handleBle(result: ScanResult) {
        val now = System.currentTimeMillis()
        val address = runCatching { result.device.address }.getOrNull() ?: "BLE-${result.hashCode()}"
        val advertisedName = result.scanRecord?.deviceName
        val deviceName = runCatching { result.device.name }.getOrNull()
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { result.device.alias }.getOrNull()
        } else {
            null
        }
        val name = resolveBluetoothName(address, advertisedName, deviceName, alias)
        val txPower = result.scanRecord?.txPowerLevel?.takeIf { it in -100..-1 } ?: -59
        val distance = estimateDistance(result.rssi, txPower)
        val manufacturerIds = result.scanRecord?.manufacturerSpecificData?.let { data ->
            (0 until data.size()).joinToString { data.keyAt(it).toString() }
        }.orEmpty()
        val services = result.scanRecord?.serviceUuids?.joinToString { it.uuid.toString().take(8) }.orEmpty()

        val detail = buildString {
            append("Bluetooth LE · RSSI ${result.rssi} dBm")
            if (manufacturerIds.isNotBlank()) append(" · Manufacturer ID $manufacturerIds")
            if (services.isNotBlank()) append(" · Services $services")
        }

        upsert(
            SeenDevice(
                id = address,
                name = name,
                type = RadioType.BLUETOOTH,
                signalDbm = result.rssi,
                distanceMeters = distance,
                address = address,
                detail = detail,
                firstSeen = now,
                lastSeen = now,
                seenCount = 1
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun handleClassicBluetoothFound(intent: Intent) {
        val device = bluetoothDeviceFromIntent(intent) ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        val rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
        if (rssiShort == Short.MIN_VALUE) return

        val advertisedName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
        val deviceName = runCatching { device.name }.getOrNull()
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { device.alias }.getOrNull()
        } else {
            null
        }
        val name = resolveBluetoothName(address, advertisedName, deviceName, alias)
        val rssi = rssiShort.toInt()
        val distance = estimateDistance(rssi, -59)
        val now = System.currentTimeMillis()

        upsert(
            SeenDevice(
                id = address,
                name = name,
                type = RadioType.BLUETOOTH,
                signalDbm = rssi,
                distanceMeters = distance,
                address = address,
                detail = "Classic Bluetooth · RSSI $rssi dBm",
                firstSeen = now,
                lastSeen = now,
                seenCount = 1
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

        devices.computeIfPresent(address) { _, old -> old.copy(name = changedName) }
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
    private fun startWifiP2pDiscovery() {
        val channel = wifiP2pChannel ?: return
        if (!hasWifiP2pPermission()) return

        runCatching {
            wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestWifiP2pPeers() {
        val channel = wifiP2pChannel ?: return
        if (!hasWifiP2pPermission()) return

        runCatching {
            wifiP2pManager.requestPeers(channel) { peerList ->
                peerList.deviceList.forEach(::handleWifiP2pDevice)
            }
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
    }

    private fun hasWifiP2pPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handleWifiP2pDevice(device: WifiP2pDevice) {
        val now = System.currentTimeMillis()
        val address = device.deviceAddress?.takeIf { it.isNotBlank() } ?: "p2p-${device.hashCode()}"
        val name = device.deviceName?.takeIf { it.isNotBlank() } ?: "Unknown Wi‑Fi Direct device"
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
                distanceMeters = null,
                address = address,
                detail = "Wi‑Fi Direct peer · $status · $type · Android does not expose peer RSSI here",
                firstSeen = now,
                lastSeen = now,
                seenCount = 1
            )
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun refreshWifi() {
        runCatching { wifiManager.startScan() }
        val results: List<WifiScanResult> = runCatching { wifiManager.scanResults }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()

        results.forEach { result ->
            val rawName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.wifiSsid?.toString()?.trim('"')
            } else {
                result.SSID
            }
            val name = rawName?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "Hidden Wi‑Fi network"
            val frequency = result.frequency
            val band = when {
                frequency >= 5925 -> "6 GHz"
                frequency >= 4900 -> "5 GHz"
                else -> "2.4 GHz"
            }
            val distance = estimateWifiDistance(result.level, frequency)
            val security = securityLabel(result.capabilities)

            upsert(
                SeenDevice(
                    id = "wifi:${result.BSSID}",
                    name = name,
                    type = RadioType.WIFI,
                    signalDbm = result.level,
                    distanceMeters = distance,
                    address = result.BSSID,
                    detail = "Wi‑Fi access point · RSSI ${result.level} dBm · $band · $security · ${frequency} MHz",
                    firstSeen = now,
                    lastSeen = now,
                    seenCount = 1
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshCellular() {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return
        val cells: List<CellInfo> = runCatching { telephonyManager.allCellInfo ?: emptyList() }.getOrDefault(emptyList())
        handleCells(cells)
    }

    private fun handleCells(cells: List<CellInfo>) {
        val now = System.currentTimeMillis()
        cells.forEachIndexed { index, cell ->
            val (technology, dbm, identity) = cellSummary(cell)
            val current = cell.isRegistered
            upsert(
                SeenDevice(
                    id = "cell:$technology:$identity",
                    name = if (current) "$technology current network cell" else "$technology nearby network cell",
                    type = RadioType.CELLULAR,
                    signalDbm = dbm,
                    distanceMeters = null,
                    address = null,
                    detail = if (current) {
                        "Signal ${dbm?.let { "$it dBm" } ?: "unknown"} · Cell $identity · Your phone is connected to this network cell"
                    } else {
                        "Signal ${dbm?.let { "$it dBm" } ?: "unknown"} · Cell $identity · Visible as a possible handover cell"
                    },
                    firstSeen = now + index,
                    lastSeen = now,
                    seenCount = 1
                )
            )
        }
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
                detail = "SDR detected over USB · RF capture backend can be activated for this hardware"
            )
        }
    }

    private fun isSupportedSdr(device: UsbDevice): Boolean {
        val vendor = device.vendorId
        val product = device.productId

        // Common RTL2832U / RTL-SDR USB IDs.
        if (vendor == 0x0BDA && (product == 0x2832 || product == 0x2838)) return true

        val name = listOfNotNull(device.manufacturerName, device.productName)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        // HackRF One/Pro can be recognized reliably by product/manufacturer text.
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
                ?: "USB Wi‑Fi monitor adapter"
            PassiveWifiState(
                connected = true,
                name = product,
                detail = "USB Wi‑Fi sensor detected · passive client-frame capture requires the HEIMDALL Linux monitor bridge"
            )
        }
    }

    private fun isSupportedPassiveWifiAdapter(device: UsbDevice): Boolean {
        // ALFA AWUS036ACM / MediaTek MT7612U.
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
                newValue.copy(
                    name = preferredName,
                    firstSeen = old.firstSeen,
                    seenCount = old.seenCount + 1
                )
            }
        }
        runOnUiThread { publishSnapshot() }
    }

    private fun isGenericName(name: String): Boolean =
        name.isBlank() ||
            name == "Unknown Bluetooth device" ||
            name == "Unknown Wi‑Fi Direct device"

    private fun publishSnapshot() {
        val now = System.currentTimeMillis()
        deviceSnapshot = devices.values
            .asSequence()
            .filter { now - it.lastSeen < VISIBLE_FRESHNESS_MS }
            .filter { device ->
                when (device.type) {
                    RadioType.BLUETOOTH, RadioType.WIFI ->
                        device.distanceMeters != null && device.distanceMeters <= MAX_VISIBLE_DISTANCE_METERS
                    // Android does not expose useful ranging for these. They are shown
                    // separately/after ranged devices instead of inventing a distance.
                    RadioType.WIFI_DIRECT, RadioType.CELLULAR, RadioType.RF -> true
                }
            }
            .sortedWith(
                compareBy<SeenDevice> { it.distanceMeters ?: Double.MAX_VALUE }
                    .thenByDescending { it.lastSeen }
            )
            .toList()
    }

    private fun estimateDistance(rssi: Int, txPower: Int): Double {
        if (rssi == 0) return 99.0
        return 10.0.pow((txPower - rssi) / 22.0).coerceIn(0.1, 99.0)
    }

    private fun estimateWifiDistance(rssi: Int, frequencyMhz: Int): Double {
        if (rssi == 0 || frequencyMhz <= 0) return 99.0
        val exponent = (27.55 - 20 * kotlin.math.log10(frequencyMhz.toDouble()) + kotlin.math.abs(rssi)) / 20.0
        return 10.0.pow(exponent).coerceIn(0.1, 99.0)
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
        content = { Surface(modifier = Modifier.fillMaxSize(), color = Color.White) { content() } }
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
        Text("HEIMDALL", color = Navy, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Allow Nearby devices and Location so HEIMDALL can scan BLE, Classic Bluetooth, Wi‑Fi access points, Wi‑Fi Direct peers and cellular signals. External RF/passive Wi‑Fi sensors activate when supported hardware is present.",
            color = Muted,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
            Text("Allow scanning")
        }
    }
}

@Composable
private fun HeimdallScreen(
    devices: List<SeenDevice>,
    scanning: Boolean,
    status: String,
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
                    Text("HEIMDALL", color = Navy, fontWeight = FontWeight.Bold, fontSize = 25.sp, letterSpacing = 2.sp)
                    Text(status, color = if (scanning) Green else Muted, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CounterCard("Bluetooth ≤1m", bluetoothCount, RadioType.BLUETOOTH, Modifier.weight(1f))
                CounterCard("Wi‑Fi", wifiCount, RadioType.WIFI, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
            Text("${devices.count { isRecent(it) }} signals currently observed", fontWeight = FontWeight.SemiBold, color = Navy)
            Text("Ranged Bluetooth/Wi‑Fi APs: only estimated ≤1 m, closest first.", color = Muted, fontSize = 12.sp)
            Text("Wi‑Fi Direct/RF entries can appear without a distance when Android cannot range them.", color = Muted, fontSize = 11.sp)
            Text(
                if (passiveWifiState.connected) {
                    "Passive Wi‑Fi sensor: ${passiveWifiState.name}"
                } else {
                    "Passive Wi‑Fi clients: external monitor sensor not connected"
                },
                color = if (passiveWifiState.connected) Green else Amber,
                fontSize = 11.sp
            )
            Text(
                if (rfState.connected) "RF receiver: ${rfState.name}" else "RF receiver: not connected",
                color = if (rfState.connected) Green else Muted,
                fontSize = 11.sp
            )
        }

        HorizontalDivider(color = Color(0xFFE8ECF2))

        if (devices.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (scanning) "No nearby transmitters found yet…" else "Scanning is paused", color = Muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(devices, key = { it.id }) { device -> DeviceCard(device) }
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
                Icon(if (scanning) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
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
        colors = CardDefaults.cardColors(containerColor = if (enabled) SoftBlue else Color(0xFFF7F7F8)),
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
            Text(if (enabled) count.toString() else "—", color = if (enabled) Navy else Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DeviceCard(device: SeenDevice) {
    var expanded by remember { mutableStateOf(false) }
    val label = proximityLabel(device)
    val labelColor = when (label) {
        "Very close" -> Green
        "Nearby" -> Amber
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
                    Text(device.name, color = Navy, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(typeLabel(device.type), color = Muted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(label, color = labelColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    device.distanceMeters?.let { Text("~${formatDistance(it)}", color = Navy, fontSize = 13.sp) }
                }
            }

            Spacer(Modifier.height(7.dp))
            Text("Last seen ${relativeTime(device.lastSeen)} · Seen ${device.seenCount}×", color = Muted, fontSize = 11.sp)

            if (isIntermittent(device)) {
                Text("Intermittent signal", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

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
                device.signalDbm?.let { DetailLine("Signal", "$it dBm") }
                DetailLine("First seen", absoluteTime(device.firstSeen))
                DetailLine("Details", device.detail)
                if (device.type == RadioType.CELLULAR) {
                    Text(
                        "These are cellular network towers/cells visible to Android, not nearby phones.",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (device.type == RadioType.WIFI_DIRECT) {
                    Text(
                        "Wi‑Fi Direct discovery works without joining a router, but only devices exposing Wi‑Fi Direct can appear here.",
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
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.width(76.dp))
        Text(value, color = Navy, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

private fun typeLabel(type: RadioType) = when (type) {
    RadioType.BLUETOOTH -> "Bluetooth (Classic / BLE)"
    RadioType.WIFI -> "Wi‑Fi access point"
    RadioType.WIFI_DIRECT -> "Wi‑Fi Direct peer"
    RadioType.CELLULAR -> "Cellular network cell"
    RadioType.RF -> "General RF"
}

private fun proximityLabel(device: SeenDevice): String = when {
    device.type == RadioType.WIFI_DIRECT -> "Distance unknown"
    device.type == RadioType.RF -> signalLabel(device.signalDbm)
    device.distanceMeters == null -> signalLabel(device.signalDbm)
    device.distanceMeters < 2 -> "Very close"
    device.distanceMeters <= 5 -> "Nearby"
    else -> "Outside range"
}

private fun signalLabel(dbm: Int?): String = when {
    dbm == null -> "Observed"
    dbm >= -70 -> "Strong"
    dbm >= -90 -> "Medium"
    else -> "Weak"
}

private fun formatDistance(meters: Double): String {
    return if (meters < 10) "${(meters * 10).roundToInt() / 10.0} m" else "${meters.roundToInt()} m"
}

private fun isRecent(device: SeenDevice): Boolean = System.currentTimeMillis() - device.lastSeen < VISIBLE_FRESHNESS_MS

private fun isIntermittent(device: SeenDevice): Boolean =
    device.seenCount > 1 && System.currentTimeMillis() - device.lastSeen > VISIBLE_FRESHNESS_MS

private fun relativeTime(time: Long): String {
    val seconds = ((System.currentTimeMillis() - time) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> "now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}

private fun absoluteTime(time: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
