package com.bryanalarcon.printertest

// Each "import" pulls one class or function into scope, like Swift's "import" but
// more fine grained. In Swift you import a whole module (import Foundation); in
// Kotlin you usually import individual names.
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The connection's current status, modeled as a "sealed interface".
 *
 * A sealed interface is Kotlin's version of a Swift enum with associated values.
 * "Sealed" means the compiler knows every possible subtype, so a "when" expression
 * (Kotlin's switch) over it is exhaustive, exactly like switching over a Swift enum.
 *
 * - "data object" is a singleton case with no payload, like a Swift case with no
 *   associated value.
 * - "data class" cases carry a payload, like ".connected(deviceName: String)" in Swift.
 *   The "data" keyword auto-generates equals, hashCode and toString from the properties.
 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val deviceName: String) : ConnectionState
    data class Connected(val deviceName: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/**
 * Parsed ~HS host-status response. Null fields mean the printer's answer was
 * missing or unparseable. Field positions are the shared spec with the Mac
 * sister tool (printer_conn.py status()): CSV field 1 = paper out,
 * field 2 = paused, field 3 = label length, field 4 = formats in buffer.
 */
data class PrinterStatus(
    val paperOut: Boolean?,
    val pause: Boolean?,
    val labelLen: String?,
    val buffer: Int?,
    val raw: String
)

/**
 * Owns the raw Bluetooth Classic (SPP/RFCOMM) connection to the printer.
 * No vendor SDK involved: we open a serial style socket and write ZPL bytes to it.
 *
 * Big picture of how the pieces cooperate:
 *
 *   connect()  opens the socket, then starts two background loops:
 *     - a READER loop that logs anything the printer sends back
 *     - a KEEPALIVE loop that writes an invisible CR-LF every 15 seconds so the
 *       printer's power save timer never sees an idle link
 *   send()     writes ZPL to the socket, reconnecting first if needed
 *   a RECONNECT loop starts automatically whenever the link dies without the
 *   user having pressed Disconnect, retrying with growing delays
 *
 * Threading model: every operation that touches the socket funnels through one
 * Mutex (ioMutex), so two button presses can never interleave their bytes.
 *
 * This class is an application wide singleton (see the companion object below) so
 * that the socket survives the Activity being destroyed and recreated, which
 * Android does far more casually than iOS recreates view controllers.
 */
@SuppressLint("MissingPermission") // The UI guarantees the permission before calling in here.
class BluetoothPrinterManager private constructor( // "private constructor" forces use of get()
    context: Context,                    // Android's context object, roughly UIApplication + Bundle
    private val scope: CoroutineScope    // The "lifetime" our background work is tied to
) {

    /**
     * A companion object is Kotlin's home for what Swift calls static members.
     * There is exactly one companion instance per class.
     */
    companion object {
        // "val" is a constant reference (Swift "let"). Type comes after the name.
        // This UUID is the Bluetooth standard identifier for "serial port service".
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // "const val" is a true compile time constant, like a Swift static let of a literal.
        private const val KEEPALIVE_INTERVAL_MS = 15_000L   // underscores are digit separators
        private val KEEPALIVE_BYTES = "\r\n".toByteArray(Charsets.US_ASCII)
        private const val RECONNECT_MAX_DELAY_MS = 30_000L

        // Query timing, shared spec with the Mac tool: wait this long after a
        // write before the printer has likely started replying, then keep
        // reading until the line has been quiet for the quiet window.
        // READ_QUIET_MS was raised from 800 to 2500 to match the Mac tool's
        // Bluetooth transport - the DPP-450 over BT was observed on hardware
        // not answering ~HS within the shorter window USB comfortably meets.
        private const val POST_WRITE_SETTLE_MS = 300L
        private const val READ_QUIET_MS = 2500L

        /** ~HS response parsing, identical to the Mac tool's status(). */
        fun parseStatus(text: String): PrinterStatus {
            val lines = text.trim().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return PrinterStatus(null, null, null, null, text)
            val fields = lines[0].split(",")
            if (fields.size < 5) return PrinterStatus(null, null, null, null, text)
            return PrinterStatus(
                paperOut = fields[1].trim() != "0",
                pause = fields[2].trim() != "0",
                labelLen = fields[3].trim(),
                buffer = fields[4].trim().toIntOrNull() ?: 0,
                raw = text
            )
        }

        // The singleton slot. @Volatile makes writes visible across threads immediately.
        @Volatile
        private var instance: BluetoothPrinterManager? = null

        /**
         * Returns the one shared manager, creating it on first use.
         * This is the classic double checked locking pattern:
         * "instance ?: ..." means "if instance is not null use it, otherwise run the
         * right hand side" (?: is the "elvis operator", Swift's ?? nil coalescing).
         */
        fun get(context: Context): BluetoothPrinterManager =
            instance ?: synchronized(this) {
                instance ?: BluetoothPrinterManager(
                    // applicationContext outlives any screen, so the singleton never
                    // accidentally keeps a dead Activity alive (a common leak).
                    context.applicationContext,
                    // A scope defines how long background work may live. SupervisorJob
                    // means one failed child coroutine does not cancel its siblings.
                    CoroutineScope(SupervisorJob() + Dispatchers.Default)
                ).also { instance = it } // .also runs a side effect and returns the object
            }
    }

    // "?" marks a nullable type, same idea as Swift optionals. Devices without
    // Bluetooth hardware return null here.
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // "var" is mutable (Swift "var"). These hold the live socket and the background jobs.
    // A Job is a handle to a running coroutine, used to cancel it later.
    private var socket: BluetoothSocket? = null
    private var readerJob: Job? = null
    private var keepaliveJob: Job? = null
    private var reconnectJob: Job? = null

    /** The device the most recent connect targeted. Auto reconnect aims at this. */
    @Volatile
    private var lastDevice: BluetoothDevice? = null

    /**
     * Set to true only when the user explicitly disconnects. The auto reconnect
     * loop checks this so it never fights the user's intent.
     */
    @Volatile
    private var userDisconnected = false

    /**
     * A Mutex is a suspending lock: while one coroutine holds it, others calling
     * withLock pause (without blocking their thread) until it is free. It plays the
     * same role Swift actors play, serializing access to the socket.
     */
    private val ioMutex = Mutex()

    /**
     * StateFlow is the observable state primitive of Kotlin coroutines, closest to
     * a Combine CurrentValueSubject or an @Published property. It always holds a
     * current value, and the UI re-renders whenever that value changes.
     *
     * The private Mutable version is writable, and the public read only view is
     * exposed through asStateFlow(). Same encapsulation you would do in Swift with
     * a private setter.
     */
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // The on screen log: an immutable List that gets replaced (not mutated) on each
    // new line. Replacing the whole value is what makes StateFlow notice the change.
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** UI toggle for the keepalive ping. Public and mutable on purpose, it is a setting. */
    val keepaliveEnabled = MutableStateFlow(true)

    /**
     * Response capture for query(): while a query is waiting, the reader loop
     * routes incoming chunks into this channel instead of logging them, so the
     * query can collect the printer's answer. A Channel is a coroutine-safe
     * queue, closest to an AsyncStream in Swift.
     */
    private val rxChannel = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var capturing = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Returns all devices paired in the phone's Bluetooth settings.
     * Return type List<BluetoothDevice> is like Swift [BluetoothDevice].
     */
    fun bondedDevices(): List<BluetoothDevice> {
        // "?: run { ... }" executes the block when adapter is null, letting us log
        // and bail out with an empty list. run { } is just an inline closure.
        val a = adapter ?: run {
            logLine("No Bluetooth adapter on this device")
            return emptyList()
        }
        if (!a.isEnabled) logLine("Bluetooth is turned off - enable it in system settings")
        return try {
            // "?." is optional chaining, identical to Swift. it.name inside the sort
            // lambda: "it" is the implicit single parameter name, like $0 in Swift.
            a.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList()
        } catch (e: SecurityException) {
            logLine("Permission error reading bonded devices: ${e.message}")
            emptyList()
        }
    }

    /**
     * "suspend" marks a function that can pause without blocking a thread, the
     * direct equivalent of Swift's "async". Callers must be inside a coroutine,
     * just like Swift async functions must be awaited from async contexts.
     *
     * Public entry point for connecting. Clears the "user wants out" flag, kills
     * any pending auto reconnect (the user is taking over), then does the real
     * work while holding the socket lock.
     */
    suspend fun connect(device: BluetoothDevice): Boolean {
        userDisconnected = false
        reconnectJob?.cancel()
        return ioMutex.withLock { connectLocked(device) }
    }

    /**
     * The actual connect logic. The "Locked" suffix is a naming convention in this
     * file: functions ending in Locked assume the caller already holds ioMutex.
     *
     * withContext(Dispatchers.IO) hops this coroutine onto a thread pool meant for
     * blocking I/O, because socket.connect() literally blocks until it succeeds or
     * fails. Think of Dispatchers.IO as DispatchQueue.global(qos: .utility) but
     * managed by the coroutine runtime.
     */
    private suspend fun connectLocked(device: BluetoothDevice): Boolean =
        withContext(Dispatchers.IO) {
            // Already connected? Nothing to do. "?." plus "== true" handles null safely:
            // if socket is null the whole expression is false.
            if (socket?.isConnected == true) {
                return@withContext true // labeled return: exits this lambda, not the fn
            }
            lastDevice = device
            val name = device.name ?: device.address
            _state.value = ConnectionState.Connecting(name)
            // "${...}" inside a string is interpolation, Swift's \(...)
            logLine("Connecting to $name (${device.address})…")
            try {
                // An in-progress Bluetooth scan slows RFCOMM connects, so cancel it.
                adapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                // cancelDiscovery needs the BLUETOOTH_SCAN permission on Android 12+.
                // If the user denied it we lose a small optimization, nothing more,
                // so this must never crash the app.
                logLine("Could not cancel discovery (BLUETOOTH_SCAN not granted) - continuing")
            }

            // Attempt 1: the standard, documented way to open an SPP socket.
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect() // blocking call, that is why we are on Dispatchers.IO
                onConnected(s, name)
                return@withContext true
            } catch (e: IOException) {
                logLine("Standard SPP connect failed: ${e.message}")
            }

            // Attempt 2: a well known workaround. Some serial printers only accept a
            // direct connection on RFCOMM channel 1. That method is hidden in the
            // Android SDK, so we invoke it through Java reflection (looking a method
            // up by name at runtime). Ugly but standard practice for SPP printers.
            try {
                logLine("Retrying via channel-1 fallback…")
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                val s = m.invoke(device, 1) as BluetoothSocket
                s.connect()
                onConnected(s, name)
                return@withContext true
            } catch (e: Exception) {
                val msg = "Connect failed: ${e.message ?: e.javaClass.simpleName}"
                logLine(msg)
                _state.value = ConnectionState.Error(msg)
                return@withContext false
            }
        }

    /** Common bookkeeping once a socket is open: publish state, start the loops. */
    private fun onConnected(s: BluetoothSocket, name: String) {
        socket = s
        _state.value = ConnectionState.Connected(name)
        logLine("Connected to $name")
        startReader(s)
        startKeepalive(s)
    }

    /**
     * Background loop that reads whatever the printer sends back. Host status
     * commands like ~HS answer over this channel, and a read error is our fastest
     * signal that the link died.
     *
     * scope.launch { } starts a new coroutine, similar to Task { } in Swift.
     */
    private fun startReader(s: BluetoothSocket) {
        readerJob?.cancel() // never run two readers at once
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            try {
                val input = s.inputStream
                // isActive flips to false when this coroutine is cancelled, so the
                // loop ends promptly on disconnect. Similar to Task.isCancelled.
                while (isActive) {
                    val n = input.read(buffer) // blocks until data arrives
                    if (n < 0) {
                        // read() returning -1 means the other side closed the stream.
                        // "===" is identity comparison (same object), Swift's "===" too.
                        // The check guards against reacting to a socket we already replaced.
                        if (socket === s) onConnectionLost("Connection closed by printer")
                        break
                    }
                    if (n > 0) {
                        if (capturing) {
                            // A query is waiting for this response; hand it over
                            // instead of logging (the query logs the whole answer
                            // once it has all of it).
                            rxChannel.trySend(buffer.copyOf(n))
                        } else {
                            logLine("RX (${n}B): ${renderBytes(buffer, n)}")
                        }
                    }
                }
            } catch (e: IOException) {
                if (isActive && socket === s) {
                    onConnectionLost("Connection lost: ${e.message}")
                }
            }
        }
    }

    /**
     * Background loop that writes CR-LF every 15 seconds while connected.
     * ZPL ignores stray control characters between label formats, so the printer
     * never prints anything for these bytes, but the radio link stays busy. That
     * prevents the printer's power save timer from dropping the connection, and if
     * the link is already dead the write throws quickly, which is how we find out.
     */
    private fun startKeepalive(s: BluetoothSocket) {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS) // suspends without holding a thread
                if (!keepaliveEnabled.value) continue  // toggle is off: skip this round
                if (socket !== s) break                // socket got replaced: stop this loop
                try {
                    // Take the same lock real prints use so a ping can never slice
                    // into the middle of a label's bytes.
                    ioMutex.withLock {
                        if (socket !== s) return@withLock
                        s.outputStream.write(KEEPALIVE_BYTES)
                        s.outputStream.flush()
                    }
                } catch (e: IOException) {
                    if (isActive && socket === s) {
                        onConnectionLost("Keepalive failed: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    /** Single funnel for "the link died on its own": log, clean up, try to get it back. */
    private fun onConnectionLost(reason: String) {
        logLine(reason)
        closeSocketQuietly()
        _state.value = ConnectionState.Disconnected
        scheduleReconnect()
    }

    /**
     * Auto reconnect loop with exponential backoff: waits 1s, 2s, 4s, 8s, 16s,
     * then 30s between attempts, forever, until it succeeds or the user disconnects.
     *
     * "1000L shl n" is a bit shift (1000 * 2^n). coerceAtMost caps the value,
     * like Swift's min(value, cap).
     */
    private fun scheduleReconnect() {
        val device = lastDevice ?: return   // nothing to reconnect to
        if (userDisconnected) return        // the user asked for this disconnect
        if (reconnectJob?.isActive == true) return // already trying
        reconnectJob = scope.launch {
            var attempt = 1
            while (isActive && !userDisconnected) {
                val delayMs =
                    (1000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                logLine("Auto-reconnect attempt $attempt in ${delayMs / 1000}s…")
                delay(delayMs)
                if (userDisconnected) break
                if (ioMutex.withLock { connectLocked(device) }) {
                    logLine("Auto-reconnect succeeded")
                    break
                }
                attempt++
            }
        }
    }

    /**
     * Sends a command and collects whatever the printer answers, by borrowing
     * the reader loop's bytes for the duration (see [capturing]). This is what
     * Check status, the recovery buttons, and the ZPL console are built on -
     * the direct equivalent of the Mac tool's _query(). Assumes ioMutex held.
     */
    private suspend fun queryLocked(label: String, zpl: String): String =
        withContext(Dispatchers.IO) {
            val s = socket
            if (s == null || !s.isConnected) {
                logLine("Not connected - cannot send \"$label\"")
                return@withContext ""
            }
            // Drain anything stale a previous command left behind, so this
            // query's answer can't get mixed with old bytes.
            while (rxChannel.tryReceive().isSuccess) { /* discard */ }
            capturing = true
            try {
                val bytes = zpl.toByteArray(Charsets.US_ASCII)
                s.outputStream.write(bytes)
                s.outputStream.flush()
                logLine("TX \"$label\" (${bytes.size} bytes)")
                delay(POST_WRITE_SETTLE_MS)
                // Collect chunks until the line has been quiet for READ_QUIET_MS.
                // withTimeoutOrNull returns null on timeout instead of throwing.
                val out = ByteArrayOutputStream()
                while (true) {
                    val chunk = withTimeoutOrNull(READ_QUIET_MS) { rxChannel.receive() } ?: break
                    out.write(chunk)
                }
                val resp = out.toByteArray()
                if (resp.isNotEmpty()) logLine("RX (${resp.size}B): ${renderBytes(resp, resp.size)}")
                String(resp, Charsets.US_ASCII)
            } catch (e: IOException) {
                val msg = "Write failed for \"$label\": ${e.message}"
                logLine(msg)
                closeSocketQuietly()
                _state.value = ConnectionState.Error(msg)
                ""
            } finally {
                capturing = false
            }
        }

    /** ~HS status query + parse. Assumes ioMutex held. */
    private suspend fun statusLocked(): PrinterStatus = parseStatus(queryLocked("~HS", "~HS"))

    /**
     * The pre-send safety guard, identical to the Mac tool's: ask the printer
     * how it's doing before throwing a job at it, and refuse if the answer
     * confirms a bad state. Exists because of a real incident - jobs stacked
     * onto a paused printer once fed through an entire roll when unpaused.
     *
     * If ~HS can't be read at all, this WARNS and returns true (send anyway)
     * rather than refusing forever: "status unreadable" and "status confirms
     * a fault" are different things, and treating them the same would
     * permanently brick printing on a printer that just doesn't answer ~HS
     * reliably over its current transport (confirmed on hardware: the
     * DPP-450 over Bluetooth). The protection only applies when a bad state
     * is actually confirmed.
     */
    private suspend fun guardLocked(label: String): Boolean {
        val pre = statusLocked()
        if (pre.paperOut == null) {
            logLine("Could not read printer status before \"$label\" - sending anyway")
            return true
        }
        if (pre.paperOut) {
            logLine("Refusing to send \"$label\": printer reports paper out")
            return false
        }
        if (pre.pause == true) {
            logLine("Refusing to send \"$label\": printer is paused - use Resume first")
            return false
        }
        if ((pre.buffer ?: 0) > 0) {
            logLine(
                "Refusing to send \"$label\": ${pre.buffer} format(s) already queued - " +
                    "use Cancel jobs first (stacking jobs caused a runaway print previously)"
            )
            return false
        }
        return true
    }

    /** Keep-open-mode command with response capture (recovery buttons, console). */
    suspend fun command(label: String, zpl: String): String = ioMutex.withLock {
        val device = lastDevice
        if (device != null) userDisconnected = false
        if (socket?.isConnected != true) {
            if (device == null) {
                logLine("Not connected - cannot send \"$label\"")
                return@withLock ""
            }
            if (!connectLocked(device)) return@withLock ""
        }
        queryLocked(label, zpl)
    }

    /** Per-print-mode command: connect, query, close. */
    suspend fun connectCommandDisconnect(device: BluetoothDevice, label: String, zpl: String): String {
        userDisconnected = true
        reconnectJob?.cancel()
        return ioMutex.withLock {
            if (!connectLocked(device)) return@withLock ""
            val response = queryLocked(label, zpl)
            delay(500)
            disconnectLocked()
            response
        }
    }

    /**
     * The main "print this" entry point, used in keep-connection-open mode.
     *
     * A payload is a list of byte chunks ("writes"). Most tests are a single chunk;
     * a few (Code Set 18's probes) intentionally send several chunks with a pause
     * between them, because command timing matters for what they test.
     *
     * Behavior promises:
     * - if the link is down, connect first
     * - unless bypassGuard is set, the ~HS pre-send guard runs first (console
     *   sends and the recovery buttons bypass it - they must work on a printer
     *   the guard would refuse)
     * - if the very first chunk fails to send, reconnect and retry the whole payload
     *   once (nothing reached the printer, so a retry cannot double print)
     * - if a later chunk fails, report failure without retrying (a retry would
     *   reprint the chunks that already went out)
     *
     * "= 0L" gives gapMs a default value, so callers with one chunk can omit it.
     * Kotlin uses default parameters where Swift would also use default parameters.
     */
    suspend fun send(
        label: String,
        writes: List<String>,
        gapMs: Long = 0L,
        bypassGuard: Boolean = false
    ): Boolean =
        ioMutex.withLock {
            val device = lastDevice
            // Pressing Print means the user wants the link up, so re-arm auto reconnect
            // even if they had pressed Disconnect earlier.
            if (device != null) userDisconnected = false
            if (socket?.isConnected != true) {
                if (device == null) {
                    logLine("Not connected - cannot send \"$label\"")
                    return@withLock false
                }
                logLine("Not connected - connecting before send…")
                if (!connectLocked(device)) return@withLock false
            }
            if (!bypassGuard && !guardLocked(label)) return@withLock false
            var failedAt = sendWritesLocked(label, writes, gapMs)
            if (failedAt == 0 && device != null && !userDisconnected) {
                logLine("Retrying \"$label\" after reconnect…")
                if (connectLocked(device)) failedAt = sendWritesLocked(label, writes, gapMs)
            }
            failedAt == -1 // the last expression of a lambda is its return value
        }

    /** Convenience overload for the common single chunk case. */
    suspend fun send(label: String, zpl: String): Boolean = send(label, listOf(zpl))

    /**
     * Sends each chunk in order, pausing gapMs between chunks.
     * Returns the index of the chunk that failed, or -1 if everything went out.
     * Multi chunk sends get a "[2/7]" style suffix in the log so you can see
     * exactly how far a sequence got before a failure or lockup.
     */
    private suspend fun sendWritesLocked(label: String, writes: List<String>, gapMs: Long): Int {
        // forEachIndexed hands the lambda both the index and the element,
        // like "for (i, chunk) in writes.enumerated()" in Swift.
        writes.forEachIndexed { i, chunk ->
            val part = if (writes.size > 1) "$label [${i + 1}/${writes.size}]" else label
            if (!sendLocked(part, chunk)) return i
            if (gapMs > 0 && i < writes.lastIndex) delay(gapMs)
        }
        return -1
    }

    /**
     * The lowest level write: string to ASCII bytes to socket. One consistent
     * charset (US_ASCII) everywhere, per the project's encoding decision.
     * On failure it tears the socket down and reports an Error state; the caller
     * decides whether to retry.
     */
    private suspend fun sendLocked(label: String, zpl: String): Boolean =
        withContext(Dispatchers.IO) {
            val s = socket
            if (s == null || !s.isConnected) {
                logLine("Not connected - cannot send \"$label\"")
                return@withContext false
            }
            try {
                val bytes = zpl.toByteArray(Charsets.US_ASCII)
                s.outputStream.write(bytes)
                s.outputStream.flush()
                logLine("TX \"$label\" (${bytes.size} bytes)")
                true
            } catch (e: IOException) {
                val msg = "Write failed for \"$label\": ${e.message}"
                logLine(msg)
                closeSocketQuietly()
                _state.value = ConnectionState.Error(msg)
                false
            }
        }

    /**
     * The whole lifecycle in one call: connect, send, wait for the bytes to drain,
     * close. Used by the "reconnect on every print" strategy toggle.
     * Marks the disconnect as intentional so auto reconnect stays quiet afterwards.
     */
    suspend fun connectSendDisconnect(
        device: BluetoothDevice,
        label: String,
        writes: List<String>,
        gapMs: Long = 0L,
        bypassGuard: Boolean = false
    ): Boolean {
        userDisconnected = true
        reconnectJob?.cancel()
        return ioMutex.withLock {
            if (!connectLocked(device)) return@withLock false
            if (!bypassGuard && !guardLocked(label)) {
                disconnectLocked()
                return@withLock false
            }
            val ok = sendWritesLocked(label, writes, gapMs) == -1
            // Closing immediately after write() can discard bytes still in transit,
            // so give the stack half a second to hand everything to the printer.
            delay(500)
            disconnectLocked()
            ok
        }
    }

    /** User initiated disconnect: remember the intent, stop reconnecting, close. */
    suspend fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        ioMutex.withLock { disconnectLocked() }
    }

    private suspend fun disconnectLocked() = withContext(Dispatchers.IO) {
        if (socket != null) logLine("Disconnected")
        closeSocketQuietly()
        _state.value = ConnectionState.Disconnected
    }

    /**
     * Cancels the background loops and closes the socket, swallowing the close
     * error because there is nothing useful to do with it.
     * "catch (_: IOException)" uses underscore to say "I am ignoring this on purpose".
     */
    private fun closeSocketQuietly() {
        readerJob?.cancel()
        readerJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    /**
     * Appends one timestamped line to the log. Note it builds a NEW list
     * ("current list + new element") instead of mutating, because StateFlow only
     * notifies observers when the value object itself is replaced.
     */
    private fun logLine(message: String) {
        _log.value = _log.value + "${timeFormat.format(Date())}  $message"
    }

    /**
     * Renders received bytes for the log: printable ASCII shows as text, anything
     * else as <hex>. "b in 0x20..0x7E" checks membership in a range, like Swift's
     * (0x20...0x7E).contains(b).
     */
    private fun renderBytes(buffer: ByteArray, length: Int): String {
        val text = StringBuilder()
        for (i in 0 until length) { // "until" excludes the end, like ..< in Swift
            val b = buffer[i].toInt() and 0xFF // bytes are signed in Kotlin; mask to 0..255
            if (b in 0x20..0x7E) text.append(b.toChar())
            else text.append("<%02X>".format(b))
        }
        return text.toString()
    }
}
