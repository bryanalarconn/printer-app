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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * State flow of this screen: App() decides which of three UIs to show
 * (permission gate, device list, or test screen) purely from state values.
 * When a state value changes, Compose re-runs the affected functions and the
 * UI updates. Same mental model as SwiftUI: UI is a function of state.
 */

/**
 * The Activity is Android's rough equivalent of a UIViewController plus the app
 * window: the OS entry point for the UI. With Compose it is mostly a thin shell
 * whose only job is to host the composable tree.
 */
class MainActivity : ComponentActivity() {

    // "lateinit var" declares a non null property that will be assigned before
    // first use (here, in onCreate). It avoids making the type nullable just
    // because initialization happens late. Swift's closest cousin is an
    // implicitly unwrapped optional (BluetoothPrinterManager!).
    private lateinit var manager: BluetoothPrinterManager

    // "override" works like Swift's override. The "?" on Bundle? means the
    // saved state may be absent (first launch).
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fetch the app wide singleton. The connection must survive this Activity
        // being destroyed and recreated (rotation, theme change, low memory), so
        // the Activity must not own it.
        manager = BluetoothPrinterManager.get(applicationContext)
        // setContent { } is the bridge from the Activity world into Compose,
        // like UIHostingController(rootView:) bridging UIKit to SwiftUI.
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // safeDrawingPadding keeps content out of the status bar and
                    // camera cutout, SwiftUI's safe area handling made explicit.
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
 * Single expression functions use "=" instead of a braced body.
 */
private fun needsRuntimePermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The root composable. Owns the permission state and the two navigation values
 * (which device is selected, which connection strategy is active), and picks
 * which screen to show. There is no navigation library: showing a different
 * screen is literally just an if/else on state, which is all a test app needs.
 */
@Composable
fun App(manager: BluetoothPrinterManager) {
    // LocalContext.current fetches the Android Context from the composition,
    // similar to reading a SwiftUI @Environment value.
    val context = androidx.compose.ui.platform.LocalContext.current

    // remember { mutableStateOf(...) } is Compose's @State. "remember" keeps the
    // value alive across re-renders; mutableStateOf makes writes trigger re-renders.
    // The "by" keyword is property delegation: it lets us type "permissionGranted"
    // instead of "permissionGranted.value" everywhere.
    var permissionGranted by remember {
        mutableStateOf(
            // Pre-check: either we are on an old Android, or the user already granted
            // BLUETOOTH_CONNECT in a previous session.
            !needsRuntimePermission() ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // SCAN is optional: it only lets us cancel a discovery scan before connecting
    // (a speed optimization). The app is gated on CONNECT alone, but we ask for
    // both together so the connect path can use cancelDiscovery when possible.
    var scanGranted by remember {
        mutableStateOf(
            !needsRuntimePermission() ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    // The modern Android way to show a system permission dialog: register a
    // launcher plus a result callback. The trailing lambda receives a map of
    // permission name to granted/denied after the user answers.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // "in" checks map membership. Only update flags that were actually part
        // of this result, so an unrelated dialog cannot reset our state.
        if (Manifest.permission.BLUETOOTH_CONNECT in results) {
            permissionGranted = results[Manifest.permission.BLUETOOTH_CONNECT] == true
        }
        if (Manifest.permission.BLUETOOTH_SCAN in results) {
            scanGranted = results[Manifest.permission.BLUETOOTH_SCAN] == true
        }
    }

    // LaunchedEffect(Unit) runs its block once when this composable first appears,
    // like SwiftUI's .onAppear or .task. Passing Unit as the key means
    // "no dependency, never re-run".
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

    // Screen 0: the permission gate. Without CONNECT nothing else can work,
    // so we return early and render only this.
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

    // A coroutine scope tied to this composable's lifetime, for launching work
    // from button taps. Equivalent in spirit to starting a Task { } from a
    // SwiftUI button action.
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // collectAsState subscribes this UI to a StateFlow: every new value causes a
    // re-render. It is the Compose equivalent of @ObservedObject/@Published.
    val state by manager.state.collectAsState()

    // Which paired device the user picked. null means "still on the picker screen".
    // The explicit <BluetoothDevice?> tells the compiler the state can hold null.
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }

    // The experiment toggle: hold one socket open across prints, or open and close
    // a fresh socket for every print. Both strategies are under test in this app.
    var keepConnectionOpen by remember { mutableStateOf(true) }

    // Copy to a local val so Kotlin can "smart cast" it: after the null check the
    // compiler treats "device" as non null inside the else branch. It cannot do
    // that with the delegated property directly because another thread could
    // theoretically change it between the check and the use.
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
            keepConnectionOpen = keepConnectionOpen,
            onToggleMode = { keep ->
                keepConnectionOpen = keep
                // Switching into per-print mode should not leave a stale socket open.
                if (!keep) scope.launch { manager.disconnect() }
            },
            onChangeDevice = {
                scope.launch { manager.disconnect() }
                selectedDevice = null // setting this back to null shows the picker again
            }
        )
    }
}

/**
 * Screen 1: a settings-app style list of paired devices. Tapping one connects to
 * it; nothing happens automatically. On success the parent is told through the
 * onConnected callback, which is how child composables communicate upward
 * (identical to passing closures into a SwiftUI subview).
 */
@SuppressLint("MissingPermission") // permission is guaranteed by the gate in App()
@Composable
fun DeviceListScreen(
    manager: BluetoothPrinterManager,
    state: ConnectionState,
    onConnected: (BluetoothDevice) -> Unit // "(A) -> Unit" is Swift's (A) -> Void
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var devices by remember { mutableStateOf(manager.bondedDevices()) }
    // While a connect attempt is running this holds that device's MAC address,
    // used to show a spinner on the right row and to ignore extra taps.
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

        // LazyColumn builds rows on demand as you scroll, like SwiftUI's List.
        // weight(1f) means "take all vertical space the fixed siblings do not use",
        // which pins the log panel to the bottom.
        LazyColumn(modifier = Modifier.weight(1f)) {
            // items(...) generates one composable per element. The key lambda gives
            // each row a stable identity (like ForEach(id:)) so Compose can reuse rows.
            items(devices, key = { it.address }) { device ->
                val isConnecting = connectingAddress == device.address
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Disable all taps while any connect is in flight.
                        .clickable(enabled = connectingAddress == null) {
                            connectingAddress = device.address
                            // Button callbacks cannot suspend, so hop into a coroutine
                            // to call the suspending connect(). Same pattern as
                            // wrapping await calls in Task { } inside a SwiftUI action.
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

        // "is" both checks the type and smart casts: inside this branch, "state"
        // is known to be Error, so state.message is accessible. This is Kotlin's
        // version of "if case .error(let message) = state" in Swift.
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
 * Screen 2: the actual test harness. Status header, strategy toggles, utility
 * buttons, the scrollable list of all 49 code sets, and the log pinned at the
 * bottom.
 */
@Composable
fun TestScreen(
    manager: BluetoothPrinterManager,
    device: BluetoothDevice,
    state: ConnectionState,
    keepConnectionOpen: Boolean,
    onToggleMode: (Boolean) -> Unit,
    onChangeDevice: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val log by manager.log.collectAsState()
    // Blocks all Print buttons while a send is running, so payloads cannot overlap.
    var busy by remember { mutableStateOf(false) }
    // When on, every print carries the settings reset block along with it.
    // Turn OFF when deliberately testing settings persistence (code sets 19-23, 28).
    var autoRestore by remember { mutableStateOf(true) }

    // Local functions: Kotlin lets you nest functions inside functions. These two
    // close over the state above (busy, scope, device), like nested funcs in Swift.

    // The one funnel through which every payload leaves this screen. Picks the
    // connection strategy based on the toggle.
    fun sendPayload(label: String, writes: List<String>, gapMs: Long = 0L) {
        if (busy) return
        busy = true
        scope.launch {
            if (keepConnectionOpen) {
                manager.send(label, writes, gapMs)
            } else {
                manager.connectSendDisconnect(device, label, writes, gapMs)
            }
            busy = false
        }
    }

    fun sendSection(codeSet: CodeSet, section: ZplSection) {
        // The restore block rides along as the final write of the same sequence,
        // so ordering is guaranteed in both connection modes.
        val label = "${codeSet.name} / ${section.label}" +
            if (autoRestore) " +restore" else ""
        val writes =
            if (autoRestore) section.writes + RESTORE_DEFAULTS_ZPL else section.writes
        sendPayload(label, writes, section.gapMs)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- Status header ----
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        // "when" is Kotlin's switch, and as an EXPRESSION it returns a
                        // value, so the whole block computes the status string.
                        // Because ConnectionState is sealed, the compiler enforces
                        // that every case is covered.
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
                // Manual connect/disconnect only makes sense in keep-open mode;
                // per-print mode manages the socket by itself.
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

            // ---- Keepalive toggle (only relevant while holding a connection) ----
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

            // ---- Utility buttons ----
            // Unpause: recover from the pause tests (code sets 13/19).
            // Clear queue: flush every buffered format.
            // Restore defaults: manual settings reset if output ever looks wrong.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { sendPayload("Unpause (~PS)", listOf(UNPAUSE_ZPL)) }
                ) { Text("Unpause") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { sendPayload("Clear queue (~JA)", listOf(CANCEL_ALL_ZPL)) }
                ) { Text("Clear queue") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { sendPayload("Restore defaults", listOf(RESTORE_DEFAULTS_ZPL)) }
                ) { Text("Restore defaults") }
            }
        }
        HorizontalDivider()

        // ---- The 49 code sets ----
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(ALL_CODE_SETS) { _, codeSet -> // "_" discards the unused index
                CodeSetRow(codeSet, busy) { section -> sendSection(codeSet, section) }
                HorizontalDivider()
            }
        }

        LogPanel(log, onClear = { manager.clearLog() })
    }
}

/**
 * One row of the code set list. Single section sets show a Print button directly;
 * multi section sets expand on tap to reveal one button per section, because the
 * source document requires sections to be run independently.
 */
@Composable
fun CodeSetRow(codeSet: CodeSet, busy: Boolean, onSend: (ZplSection) -> Unit) {
    if (codeSet.sections.size == 1) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(codeSet.name, modifier = Modifier.weight(1f))
            Button(enabled = !busy, onClick = { onSend(codeSet.sections[0]) }) { Text("Print") }
        }
    } else {
        // remember(key) resets this expansion state if the row is ever reused for a
        // different code set (keyed the same way SwiftUI identifies ForEach content).
        var expanded by remember(codeSet.name) { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(codeSet.name, modifier = Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼ ${codeSet.sections.size} sections")
            }
            if (expanded) {
                codeSet.sections.forEach { section ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 16.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            section.label,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Button(enabled = !busy, onClick = { onSend(section) }) { Text("Print") }
                    }
                }
            }
        }
    }
}

/**
 * The timestamped event log pinned to the bottom of both screens. This is the
 * reliability dashboard: every TX, RX, drop, reconnect attempt and error lands here.
 */
@Composable
fun LogPanel(log: List<String>, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    // Auto scroll to the newest line whenever one arrives. LaunchedEffect keyed on
    // log.size re-runs exactly when the line count changes.
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
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
                    fontFamily = FontFamily.Monospace, // fixed width font keeps columns aligned
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
