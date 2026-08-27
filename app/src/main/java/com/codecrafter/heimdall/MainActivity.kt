package com.codecrafter.heimdall

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
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

private const val BLE_SCAN_WINDOW_MS = 10_000L
private const val SCAN_CYCLE_MS = 60_000L

private enum class RadioType { BLUETOOTH, WIFI, CELLULAR }

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

class MainActivity : ComponentActivity() {
    private val devices = ConcurrentHashMap<String, SeenDevice>()
    private var deviceSnapshot by mutableStateOf<List<SeenDevice>>(emptyList())
    private var scanning by mutableStateOf(false)
    private var statusText by mutableStateOf("Preparing scanner…")
    private var scanJob: Job? = null

    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    private val telephonyManager by lazy { getSystemService(TelephonyManager::class.java) }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleBle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleBle)
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { statusText = "Bluetooth scan unavailable ($errorCode)" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeimdallTheme {
                PermissionGate(
                    onPermissionsReady = { startScanning() },
                    content = {
                        HeimdallScreen(
                            devices = deviceSnapshot,
                            scanning = scanning,
                            status = statusText,
                            onToggle = { if (scanning) pauseScanning() else startScanning() }
                        )
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        pauseScanning()
        super.onDestroy()
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
        }.toTypedArray()

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            // Changing this Compose state forces an immediate permission re-check.
            // The previous version did not recompose here, so the Allow screen could stay stuck.
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
            PermissionScreen {
                launcher.launch(missing.toTypedArray())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (scanning) return

        scanning = true
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            while (isActive && scanning) {
                val bluetoothStarted = startBleWindow()
                statusText = if (bluetoothStarted) {
                    "Scanning nearby signals…"
                } else {
                    "Scanning available signals · Bluetooth off"
                }

                refreshWifi()
                refreshCellular()
                publishSnapshot()

                delay(BLE_SCAN_WINDOW_MS)
                stopBle()
                publishSnapshot()

                if (scanning) {
                    statusText = "Waiting for next scan…"
                }

                delay(SCAN_CYCLE_MS - BLE_SCAN_WINDOW_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pauseScanning() {
        scanning = false
        scanJob?.cancel()
        scanJob = null
        stopBle()
        statusText = "Paused"
    }

    @SuppressLint("MissingPermission")
    private fun startBleWindow(): Boolean {
        val adapter: BluetoothAdapter = bluetoothManager.adapter ?: return false
        if (!adapter.isEnabled) return false

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return runCatching {
            adapter.bluetoothLeScanner?.startScan(null, settings, bleCallback)
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        runCatching {
            bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(bleCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBle(result: ScanResult) {
        val now = System.currentTimeMillis()
        val address = runCatching { result.device.address }.getOrNull()
            ?: "BLE-${result.hashCode()}"
        val advertisedName = result.scanRecord?.deviceName
        val deviceName = runCatching { result.device.name }.getOrNull()
        val name = advertisedName ?: deviceName ?: "Unknown Bluetooth device"
        val txPower = result.scanRecord?.txPowerLevel?.takeIf { it in -100..-1 } ?: -59
        val distance = estimateDistance(result.rssi, txPower)
        val manufacturerIds = result.scanRecord?.manufacturerSpecificData?.let { data ->
            (0 until data.size()).joinToString { data.keyAt(it).toString() }
        }.orEmpty()
        val services = result.scanRecord?.serviceUuids
            ?.joinToString { it.uuid.toString().take(8) }
            .orEmpty()

        val detail = buildString {
            append("RSSI ${result.rssi} dBm")
            if (manufacturerIds.isNotBlank()) {
                append(" · Manufacturer ID $manufacturerIds")
            }
            if (services.isNotBlank()) {
                append(" · Services $services")
            }
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
    @Suppress("DEPRECATION")
    private fun refreshWifi() {
        runCatching { wifiManager.startScan() }

        val results: List<WifiScanResult> = runCatching {
            wifiManager.scanResults
        }.getOrDefault(emptyList())

        val now = System.currentTimeMillis()
        results.forEach { result ->
            val rawName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.wifiSsid?.toString()?.trim('"')
            } else {
                result.SSID
            }
            val name = rawName
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                ?: "Hidden Wi‑Fi network"

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
                    detail = "RSSI ${result.level} dBm · $band · $security · ${frequency} MHz",
                    firstSeen = now,
                    lastSeen = now,
                    seenCount = 1
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshCellular() {
        val hasLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation) return

        val cells: List<CellInfo> = runCatching {
            telephonyManager.allCellInfo ?: emptyList()
        }.getOrDefault(emptyList())

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
                    name = if (current) {
                        "$technology current network cell"
                    } else {
                        "$technology nearby network cell"
                    },
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

    private fun upsert(newValue: SeenDevice) {
        devices.compute(newValue.id) { _, old ->
            if (old == null) {
                newValue
            } else {
                newValue.copy(
                    firstSeen = old.firstSeen,
                    seenCount = old.seenCount + 1
                )
            }
        }

        runOnUiThread { publishSnapshot() }
    }

    private fun publishSnapshot() {
        val now = System.currentTimeMillis()
        deviceSnapshot = devices.values
            .filter { now - it.lastSeen < 24 * 60 * 60 * 1000L }
            .sortedWith(
                compareBy<SeenDevice> { proximityRank(it) }
                    .thenByDescending { it.lastSeen }
            )
    }

    private fun proximityRank(device: SeenDevice): Int = when {
        device.distanceMeters == null -> 4
        device.distanceMeters < 2 -> 0
        device.distanceMeters < 5 -> 1
        device.distanceMeters < 10 -> 2
        else -> 3
    }

    private fun estimateDistance(rssi: Int, txPower: Int): Double {
        if (rssi == 0) return 99.0
        return 10.0.pow((txPower - rssi) / 22.0).coerceIn(0.1, 99.0)
    }

    private fun estimateWifiDistance(rssi: Int, frequencyMhz: Int): Double {
        if (rssi == 0 || frequencyMhz <= 0) return 99.0
        val exponent = (
            27.55 -
                20 * kotlin.math.log10(frequencyMhz.toDouble()) +
                kotlin.math.abs(rssi)
            ) / 20.0
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
        content = {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.White
            ) {
                content()
            }
        }
    )
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
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
            "Allow Nearby devices and Location so HEIMDALL can find Bluetooth, Wi‑Fi and cellular network signals.",
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
    onToggle: () -> Unit
) {
    val bluetoothCount = devices.count {
        it.type == RadioType.BLUETOOTH && isRecent(it)
    }
    val wifiCount = devices.count {
        it.type == RadioType.WIFI && isRecent(it)
    }
    val cellularCount = devices.count {
        it.type == RadioType.CELLULAR && isRecent(it)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(
                horizontal = 20.dp,
                vertical = 18.dp
            )
        ) {
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

            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CounterCard(
                    "Bluetooth",
                    bluetoothCount,
                    RadioType.BLUETOOTH,
                    Modifier.weight(1f)
                )
                CounterCard(
                    "Wi‑Fi",
                    wifiCount,
                    RadioType.WIFI,
                    Modifier.weight(1f)
                )
                CounterCard(
                    "Cellular",
                    cellularCount,
                    RadioType.CELLULAR,
                    Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "${devices.count { isRecent(it) }} signals currently observed",
                fontWeight = FontWeight.SemiBold,
                color = Navy
            )
            Text(
                "Tap a device for details. Distance is an estimate.",
                color = Muted,
                fontSize = 12.sp
            )
        }

        HorizontalDivider(color = Color(0xFFE8ECF2))

        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (scanning) "Looking for nearby signals…" else "Scanning is paused",
                    color = Muted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(devices, key = { it.id }) { device ->
                    DeviceCard(device)
                }
            }
        }

        Button(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Navy)
        ) {
            Icon(
                if (scanning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (scanning) "Pause" else "Resume",
                fontSize = 16.sp
            )
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
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SoftBlue),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Icon(
                imageVector = when (type) {
                    RadioType.BLUETOOTH -> Icons.Default.Bluetooth
                    RadioType.WIFI -> Icons.Default.Wifi
                    RadioType.CELLULAR -> Icons.Default.CellTower
                },
                contentDescription = null,
                tint = Blue
            )
            Spacer(Modifier.height(7.dp))
            Text(
                count.toString(),
                color = Navy,
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
        "Very close" -> Green
        "Nearby" -> Amber
        else -> Muted
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFBFD)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (device.type) {
                        RadioType.BLUETOOTH -> Icons.Default.Bluetooth
                        RadioType.WIFI -> Icons.Default.Wifi
                        RadioType.CELLULAR -> Icons.Default.CellTower
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
                    Text(
                        typeLabel(device.type),
                        color = Muted,
                        fontSize = 12.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        label,
                        color = labelColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    device.distanceMeters?.let {
                        Text(
                            "~${formatDistance(it)}",
                            color = Navy,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(7.dp))
            Text(
                "Last seen ${relativeTime(device.lastSeen)} · Seen ${device.seenCount}×",
                color = Muted,
                fontSize = 11.sp
            )

            if (isIntermittent(device)) {
                Text(
                    "Intermittent signal",
                    color = Amber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Color(0xFFE5EAF1))
                Spacer(Modifier.height(10.dp))

                device.address?.let {
                    DetailLine(
                        if (device.type == RadioType.WIFI) "BSSID" else "Address",
                        it
                    )
                }
                device.signalDbm?.let {
                    DetailLine("Signal", "$it dBm")
                }
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
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            label,
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.width(76.dp)
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
    RadioType.BLUETOOTH -> "Bluetooth / BLE"
    RadioType.WIFI -> "Wi‑Fi"
    RadioType.CELLULAR -> "Cellular network cell"
}

private fun proximityLabel(device: SeenDevice): String = when {
    device.distanceMeters == null -> signalLabel(device.signalDbm)
    device.distanceMeters < 2 -> "Very close"
    device.distanceMeters < 5 -> "Nearby"
    device.distanceMeters < 10 -> "Far"
    else -> "Weak signal"
}

private fun signalLabel(dbm: Int?): String = when {
    dbm == null -> "Observed"
    dbm >= -70 -> "Strong"
    dbm >= -90 -> "Medium"
    else -> "Weak"
}

private fun formatDistance(meters: Double): String {
    return if (meters < 10) {
        "${(meters * 10).roundToInt() / 10.0} m"
    } else {
        "${meters.roundToInt()} m"
    }
}

private fun isRecent(device: SeenDevice): Boolean {
    return System.currentTimeMillis() - device.lastSeen < 90_000
}

private fun isIntermittent(device: SeenDevice): Boolean {
    return device.seenCount > 1 &&
        System.currentTimeMillis() - device.lastSeen > 90_000
}

private fun relativeTime(time: Long): String {
    val seconds = ((System.currentTimeMillis() - time) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> "now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}

private fun absoluteTime(time: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
}
