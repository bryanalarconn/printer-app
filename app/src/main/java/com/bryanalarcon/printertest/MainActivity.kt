package com.bryanalarcon.printertest

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/*
 * READING THIS FILE IF YOU KNOW SWIFTUI
 *
 * Jetpack Compose is Android's SwiftUI. The mapping is nearly one to one:
 *
 *   @Composable fun Thing() { ... }   ==  a SwiftUI View's body
 *   Column / Row / Box               ==  VStack / HStack / ZStack
 *   Modifier.padding(16.dp)          ==  .padding(16)
 *   remember { mutableStateOf(x) }   ==  @State private var
 *   LazyColumn                       ==  List / LazyVStack
 *   LaunchedEffect(key) { ... }      ==  .task(id: key) { ... }
 *
 * One Kotlin syntax rule explains most of what you see below: when the LAST
 * parameter of a function is a lambda, it moves outside the parentheses. So
 * Column(modifier = ...) { Text("hi") } is a call to Column() whose final
 * "content" parameter is the { Text("hi") } block. Swift has the same feature
 * (trailing closures), Compose just uses it everywhere.
 *
 * SISTER-APP NOTE: this screen mirrors the Mac tool (mac_printer_tool) - the
 * same buttons with the same labels and captions, the same dropdown options,
 * the same tabs. When wording changes in one app it must change in the other.
 */

/**
 * The Activity is Android's rough equivalent of a UIViewController plus the app
 * window: the OS entry point for the UI. With Compose it is mostly a thin shell
 * whose only job is to host the composable tree.
 */
class MainActivity : ComponentActivity() {

    private lateinit var manager: BluetoothPrinterManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fetch the app wide singleton. The connection must survive this Activity
        // being destroyed and recreated (rotation, theme change, low memory), so
        // the Activity must not own it.
        manager = BluetoothPrinterManager.get(applicationContext)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.safeDrawingPadding()) {
                        App(manager)
                    }
                }
            }
        }
    }
}

/**
 * Runtime permission prompts only exist on Android 12 (API 31) and newer for
 * Bluetooth. Older versions granted Bluetooth at install time.
 */
private fun needsRuntimePermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The root composable. Owns the permission state and the two navigation values
 * (which device is selected, which connection strategy is active), and picks
 * which screen to show.
 */
@Composable
fun App(manager: BluetoothPrinterManager) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var permissionGranted by remember {
        mutableStateOf(
            !needsRuntimePermission() ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var scanGranted by remember {
        mutableStateOf(
            !needsRuntimePermission() ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (Manifest.permission.BLUETOOTH_CONNECT in results) {
            permissionGranted = results[Manifest.permission.BLUETOOTH_CONNECT] == true
        }
        if (Manifest.permission.BLUETOOTH_SCAN in results) {
            scanGranted = results[Manifest.permission.BLUETOOTH_SCAN] == true
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted || !scanGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        }
    }

    if (!permissionGranted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Bluetooth permission is required to talk to the printer.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                )
            }) { Text("Grant permission") }
        }
        return
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state by manager.state.collectAsState()
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var keepConnectionOpen by remember { mutableStateOf(true) }

    // The 49 code sets come from assets/code_sets/, a synced copy of the
    // canonical folder shared with the Mac tool. remember { } loads them once.
    val codeSets = remember { CodeSetLoader.load(context) }

    val device = selectedDevice
    if (device == null) {
        DeviceListScreen(
            manager = manager,
            state = state,
            onConnected = { selectedDevice = it }
        )
    } else {
        TestScreen(
            manager = manager,
            device = device,
            state = state,
            codeSets = codeSets,
            keepConnectionOpen = keepConnectionOpen,
            onToggleMode = { keep ->
                keepConnectionOpen = keep
                if (!keep) scope.launch { manager.disconnect() }
            },
            onChangeDevice = {
                scope.launch { manager.disconnect() }
                selectedDevice = null
            }
        )
    }
}

/**
 * Screen 1: a settings-app style list of paired devices. Tapping one connects
 * to it; nothing happens automatically.
 */
@SuppressLint("MissingPermission") // permission is guaranteed by the gate in App()
@Composable
fun DeviceListScreen(
    manager: BluetoothPrinterManager,
    state: ConnectionState,
    onConnected: (BluetoothDevice) -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var devices by remember { mutableStateOf(manager.bondedDevices()) }
    var connectingAddress by remember { mutableStateOf<String?>(null) }
    val log by manager.log.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Paired devices", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { devices = manager.bondedDevices() }) { Text("Refresh") }
        }
        Text(
            "Tap a device to connect. Pair the DPP-450 in system Bluetooth settings first.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices, key = { it.address }) { device ->
                val isConnecting = connectingAddress == device.address
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = connectingAddress == null) {
                            connectingAddress = device.address
                            scope.launch {
                                val ok = manager.connect(device)
                                connectingAddress = null
                                if (ok) onConnected(device)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name ?: "(no name)", fontWeight = FontWeight.Medium)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                        if (isConnecting) CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                    }
                }
                HorizontalDivider()
            }
            if (devices.isEmpty()) {
                item {
                    Text(
                        "No paired devices found.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        if (state is ConnectionState.Error) {
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        LogPanel(log, onClear = { manager.clearLog() })
    }
}

/**
 * Screen 2: the test harness. Status header with live printer flags, strategy
 * toggles, Media/Print-mode dropdowns, the five utility buttons, then two tabs
 * (code sets / ZPL console) with the log pinned below - the same layout and
 * wording as the Mac tool's test page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestScreen(
    manager: BluetoothPrinterManager,
    device: BluetoothDevice,
    state: ConnectionState,
    codeSets: List<CodeSet>,
    keepConnectionOpen: Boolean,
    onToggleMode: (Boolean) -> Unit,
    onChangeDevice: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val log by manager.log.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var autoRestore by remember { mutableStateOf(true) }
    var statusFlags by remember { mutableStateOf("") }
    // Defaults per printer are allowed to differ between the sister apps
    // (the options are identical): DPP-450 tears off; the Mac's ZD421 cuts.
    var mediaMode by remember { mutableStateOf("Continuous (receipt)") }
    var printMode by remember { mutableStateOf("Tear off") }
    var selectedTab by remember { mutableStateOf(0) }
    val consoleHistory = remember { mutableStateListOf<String>() }

    fun restoreBlock() = buildRestoreDefaults(
        MEDIA_TRACKING_MODES.getValue(mediaMode),
        PRINT_MODES.getValue(printMode)
    )

    // Local suspend helpers: route through the strategy the toggle selects.
    suspend fun runCommand(label: String, zpl: String): String =
        if (keepConnectionOpen) manager.command(label, zpl)
        else manager.connectCommandDisconnect(device, label, zpl)

    suspend fun refreshFlags() {
        val st = BluetoothPrinterManager.parseStatus(runCommand("~HS", "~HS"))
        // Same wording as the Mac tool's flags line.
        val paper = if (st.paperOut == true) "PAPER OUT" else "paper ok"
        val pause = if (st.pause == true) "PAUSED" else "not paused"
        statusFlags = "$paper  |  $pause  |  buffer: ${st.buffer}  |  label length: ${st.labelLen}"
    }

    fun checkStatus() {
        if (busy) return
        busy = true
        scope.launch {
            refreshFlags()
            busy = false
        }
    }

    /** Recovery buttons: bypass the guard (they must work on a faulted printer). */
    fun utility(label: String, zpl: String) {
        if (busy) return
        busy = true
        scope.launch {
            runCommand(label, zpl)
            // Chain a flags refresh in keep-open mode; in per-print mode that
            // would open a whole extra connection just for the flags (the Mac
            // tool skips it there too).
            if (keepConnectionOpen) refreshFlags()
            busy = false
        }
    }

    fun sendSection(codeSet: CodeSet, section: ZplSection) {
        if (busy) return
        busy = true
        scope.launch {
            // Some sets (34, 35) configure the printer's own media/print mode
            // as part of what they test. Sync the dropdowns to match BEFORE
            // computing the restore block below, so auto-restore reasserts
            // that mode instead of silently reverting it to whatever was
            // previously selected.
            codeSet.setsMedia?.let { if (it in MEDIA_TRACKING_MODES) mediaMode = it }
            codeSet.setsPrintMode?.let { if (it in PRINT_MODES) printMode = it }
            // The restore block rides along as the final write of the same
            // sequence, so ordering is guaranteed in both connection modes.
            val label = "${codeSet.name} / ${section.label}" +
                if (autoRestore) " +restore" else ""
            val writes =
                if (autoRestore) section.writes + restoreBlock() else section.writes
            if (keepConnectionOpen) {
                manager.send(label, writes, section.gapMs)
                refreshFlags()
            } else {
                manager.connectSendDisconnect(device, label, writes, section.gapMs)
            }
            busy = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- Status header ----
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = when (state) {
                            is ConnectionState.Connected -> "Connected: ${state.deviceName}"
                            is ConnectionState.Connecting -> "Connecting: ${state.deviceName}…"
                            is ConnectionState.Error -> "Error"
                            ConnectionState.Disconnected ->
                                if (keepConnectionOpen) "Disconnected" else "Idle (connects per print)"
                        },
                        fontWeight = FontWeight.Bold
                    )
                    if (state is ConnectionState.Error) {
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                TextButton(onClick = onChangeDevice) { Text("Change device") }
            }
            // Live printer flags from ~HS (Check status / after each print).
            if (statusFlags.isNotEmpty()) {
                Text(statusFlags, style = MaterialTheme.typography.bodySmall)
            }

            // ---- Connection strategy toggle ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = keepConnectionOpen, onCheckedChange = onToggleMode)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (keepConnectionOpen) "Keep connection open"
                    else "Reconnect on every print",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.weight(1f))
                if (keepConnectionOpen) {
                    val connected = state is ConnectionState.Connected
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                if (connected) manager.disconnect() else manager.connect(device)
                            }
                        }
                    ) { Text(if (connected) "Disconnect" else "Reconnect") }
                }
            }

            // ---- Keepalive toggle ----
            if (keepConnectionOpen) {
                val keepalive by manager.keepaliveEnabled.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = keepalive,
                        onCheckedChange = { manager.keepaliveEnabled.value = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Keepalive ping every 15s",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // ---- Auto-restore toggle ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoRestore, onCheckedChange = { autoRestore = it })
                Spacer(Modifier.width(8.dp))
                Text(
                    "Auto-restore defaults after print",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // ---- Media / Print mode dropdowns ----
            // Selections feed the restore block, so a chosen mode survives
            // prints instead of being reset by them. Same options as the Mac.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeDropdown(
                    caption = "Media:",
                    options = MEDIA_TRACKING_MODES.keys.toList(),
                    selected = mediaMode,
                    enabled = !busy
                ) { choice ->
                    mediaMode = choice
                    utility(
                        "Set media tracking: $choice",
                        "^XA" + MEDIA_TRACKING_MODES.getValue(choice) + "^XZ"
                    )
                }
                Spacer(Modifier.width(12.dp))
                ModeDropdown(
                    caption = "Print mode:",
                    options = PRINT_MODES.keys.toList(),
                    selected = printMode,
                    enabled = !busy
                ) { choice ->
                    printMode = choice
                    utility(
                        "Set print mode: $choice",
                        "^XA" + PRINT_MODES.getValue(choice) + "^XZ"
                    )
                }
            }

            // ---- Utility buttons (none of these print a label) ----
            // Labels and captions are shared canon with the Mac tool.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                UtilityButton("Check status", "Query ~HS and update\nthe flags above. No paper.", busy) {
                    checkStatus()
                }
                UtilityButton("Resume", "Send ~PS to clear a pause\n(fault or ^PP test). No paper.", busy) {
                    utility("Resume (~PS)", UNPAUSE_ZPL)
                }
                UtilityButton("Cancel jobs", "Send ~JA to discard every\nqueued format. No paper.", busy) {
                    utility("Cancel jobs (~JA)", CANCEL_ALL_ZPL)
                }
                UtilityButton("Cut paper", "Feed a tiny blank label\nand cut it off.", busy) {
                    utility("Cut paper", CUT_PAPER_ZPL)
                }
                UtilityButton("Reset settings", "Undo persistent settings\n(width, mirror, darkness...). No paper.", busy) {
                    utility("Reset settings", restoreBlock())
                }
            }
        }
        HorizontalDivider()

        // ---- Tabs: code sets / console ----
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Test code sets") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("ZPL console") }
            )
        }

        if (selectedTab == 0) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(codeSets) { _, codeSet ->
                    CodeSetRow(codeSet, busy) { section -> sendSection(codeSet, section) }
                    HorizontalDivider()
                }
            }
        } else {
            ConsoleTab(
                modifier = Modifier.weight(1f),
                busy = busy,
                history = consoleHistory
            ) { text ->
                if (busy) return@ConsoleTab
                busy = true
                // Console sends bypass the guard and never get the restore
                // block - it must be able to send ~PS/~JA to a faulted printer
                // and run arbitrary experiments (same rule as the Mac console).
                consoleHistory.remove(text)
                consoleHistory.add(0, text)
                scope.launch {
                    runCommand("console", text)
                    busy = false
                }
            }
        }

        LogPanel(log, onClear = { manager.clearLog() })
    }
}

/** One utility button with the Mac tool's gray caption underneath. */
@Composable
fun UtilityButton(text: String, caption: String, busy: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedButton(enabled = !busy, onClick = onClick) { Text(text, fontSize = 12.sp) }
        Text(
            caption,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
    }
}

/** Small labeled dropdown (Media: / Print mode:), like the Mac's combo boxes. */
@Composable
fun ModeDropdown(
    caption: String,
    options: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(caption, style = MaterialTheme.typography.bodyMedium)
        Box {
            TextButton(enabled = enabled, onClick = { expanded = true }) {
                Text("$selected ▾", fontSize = 12.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            if (option != selected) onSelect(option)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Free-form ZPL console, the port of the Mac tool's console tab: sends exactly
 * what you type, no safety checks, no auto-restore. Responses appear in the log.
 */
@Composable
fun ConsoleTab(
    modifier: Modifier = Modifier,
    busy: Boolean,
    history: List<String>,
    onSend: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Column(modifier = modifier.padding(12.dp)) {
        Text(
            "Sends exactly what you type - no safety checks, no auto-restore. " +
                "Responses appear in the log below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            placeholder = {
                Text(
                    "Examples:\n" +
                        "  ^XA^FO50,50^FDHello^FS^XZ\n" +
                        "  ~HS\n" +
                        "  ! U1 getvar \"ezpl.print_mode\"",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy && input.isNotBlank(),
            onClick = { onSend(input) }
        ) { Text("Send") }
        Spacer(Modifier.height(8.dp))
        Text("History (tap to reload)", style = MaterialTheme.typography.labelMedium)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(history.size) { i ->
                Text(
                    history[i].replace("\n", "  "),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { input = history[i] }
                        .padding(vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * One row of the code set list. Single section sets show their button directly
 * (text from meta.json, e.g. "Query" for the host-status sets); multi section
 * sets expand on tap to one button per section.
 */
@Composable
fun CodeSetRow(codeSet: CodeSet, busy: Boolean, onSend: (ZplSection) -> Unit) {
    if (codeSet.sections.size == 1) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(codeSet.name, modifier = Modifier.weight(1f))
                Button(enabled = !busy, onClick = { onSend(codeSet.sections[0]) }) {
                    Text(codeSet.button ?: "Print", fontSize = 12.sp)
                }
            }
            codeSet.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    } else {
        var expanded by remember(codeSet.name) { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(codeSet.name, modifier = Modifier.weight(1f))
                    Text(if (expanded) "▲" else "▼ ${codeSet.sections.size} steps")
                }
                codeSet.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (expanded) {
                codeSet.sections.forEachIndexed { i, section ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 16.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Step ${i + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(52.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        // The section label IS the action, so it goes on the button
                        // itself - same convention as the Mac tool.
                        Button(enabled = !busy, onClick = { onSend(section) }) {
                            Text(section.label, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The timestamped event log pinned to the bottom of both screens: every TX,
 * RX, drop, reconnect attempt and refusal lands here.
 */
@Composable
fun LogPanel(log: List<String>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Log", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onClear) { Text("Clear", fontSize = 12.sp) }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(log.size) { i ->
                Text(
                    log[i],
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
