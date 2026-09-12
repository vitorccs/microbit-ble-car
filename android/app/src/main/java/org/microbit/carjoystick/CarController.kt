package org.microbit.carjoystick

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LinkState { IDLE, SCANNING, CONNECTING, CONNECTED }

data class Status(val text: String, val isError: Boolean = false)

private const val LOG_LIMIT = 80  // lines kept in the diagnostic panel

/* The one setting worth outliving the process. */
private const val PREFS_NAME = "carjoystick"
private const val SINGLE_STICK_KEY = "singleStick"

/**
 * The controller owns every piece of state the UI reads and is the only place
 * that decides what goes on the wire. It runs entirely on the main dispatcher,
 * so the BLE callbacks bounce back here before touching anything.
 */
class CarController(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>()

    private val preferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(LinkState.IDLE)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _status = MutableStateFlow(Status("Bluetooth disconnected"))
    val status: StateFlow<Status> = _status.asStateFlow()

    /* ---- Where the car is being steered from ----
       The left stick gives N, S or C; the right one E, W or C. Together they
       name one of the nine directions. The keyboard is a third source and only
       gets a say once both sticks are home, so letting go of a stick hands the
       car back to a key that is still down rather than stopping it. */
    private val _left = MutableStateFlow(Stick())
    private val _right = MutableStateFlow(Stick())

    /** One stick or two. With one, the left stick steers on both axes and the
     *  right one is gone, A, B and C staying behind as a cluster. Remembered
     *  between runs, so the driver picks once. */
    private val _singleStick = MutableStateFlow(
        preferences.getBoolean(SINGLE_STICK_KEY, false),
    )
    val singleStick: StateFlow<Boolean> = _singleStick.asStateFlow()

    /** What the sticks and keys currently add up to, for the on-screen readout. */
    private val _readout = MutableStateFlow(Protocol.STOP)
    val readout: StateFlow<String> = _readout.asStateFlow()

    /** Arrow keys and gamepad D-pad currently down. */
    private var held: Set<Command> = emptySet()

    /** Action buttons have no held state, so their press just blinks. */
    private val _flashing = MutableStateFlow<Set<Command>>(emptySet())
    val flashing: StateFlow<Set<Command>> = _flashing.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _showLog = MutableStateFlow(false)
    val showLog: StateFlow<Boolean> = _showLog.asStateFlow()

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices.asStateFlow()

    private val _picking = MutableStateFlow(false)
    val picking: StateFlow<Boolean> = _picking.asStateFlow()

    /* ---- Send state ----
       Only one GATT write may be in flight, and a press can change the wanted
       speed faster than the radio can carry it. So instead of a queue of motion
       commands — which would keep the car driving after the button came up — the
       pump keeps a single *desired* state and always sends the newest one. One-shot
       actions (A/B/C) are different: every one of them has to arrive, so they get a
       real queue and jump ahead of motion. */
    private var desiredMotion = Protocol.STOP
    private var lastSentMotion: String? = null
    private var lastMotionAt = 0L
    private var lastReported = ""
    private val actionQueue = ArrayDeque<String>()

    /** Don't overwrite the "connecting..." message with a send report. */
    private var statusLock = false

    /* Conflated: the pump only ever needs to know that *something* changed. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var scanJob: Job? = null
    private var connectJob: Job? = null

    private val link = BleLink(
        context = application,
        log = { line -> viewModelScope.launch { log(line) } },
        onNotify = { text -> viewModelScope.launch { log("micro:bit said: ${text.trim()}") } },
        onDisconnected = { viewModelScope.launch { onDisconnected() } },
    )

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    init {
        viewModelScope.launch { pumpLoop() }
    }

    /* ------------------------------- Status ------------------------------- */

    fun setStatus(text: String, isError: Boolean = false) {
        _status.value = Status(text, isError)
    }

    fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date())
        _logLines.value = (_logLines.value + "$stamp  $line").takeLast(LOG_LIMIT)
        Log.d("car", line)
    }

    fun toggleLog() {
        _showLog.value = !_showLog.value
    }

    /* ------------------------------ Steering ------------------------------ */

    /** Called by an on-screen stick on every move, and on its release. */
    fun onStickMoved(side: Side, direction: String, speed: Int) {
        val flow = if (side == Side.LEFT) _left else _right
        val moved = Stick(direction, speed)
        if (moved == flow.value) return
        flow.value = moved
        steer()
    }

    /**
     * The sticks have the wheel while either is off centre; the keys take over
     * only once both are home, so letting go of a stick hands a still-held key
     * back its direction instead of stopping the car.
     *
     * Speed comes from the left stick whenever it is driving — it is the one
     * that decides how fast the car goes. Only when it is home does the right
     * stick set the pace, which is what gives a spin in place its own throttle
     * instead of a fixed rate.
     */
    private fun motionCommand(): String {
        val left = _left.value
        val right = _right.value

        /* One stick names the whole direction by itself, diagonals included, so
           there is nothing to combine — combine() only knows single axis names
           and would read "NE" as no vertical at all. */
        if (_singleStick.value) {
            if (!left.isCentred) return Protocol.motion(left.direction, left.speed)

            val (vertical, horizontal) = Protocol.axesFor(held)
            val keyed = Protocol.curveOnly(
                Protocol.combine(vertical, horizontal),
                if (vertical == "S") -1f else 1f,
            )
            return Protocol.motion(keyed, Protocol.KEY_SPEED)
        }

        val direction = Protocol.combine(left.direction, right.direction)
        if (direction == "C") {
            val (vertical, horizontal) = Protocol.axesFor(held)
            return Protocol.motion(Protocol.combine(vertical, horizontal), Protocol.KEY_SPEED)
        }

        return Protocol.motion(direction, if (!left.isCentred) left.speed else right.speed)
    }

    /** Work out the new command, show it, and queue it. */
    private fun steer() {
        val command = motionCommand()
        _readout.value = command
        setMotion(command)
    }

    /** Swap between one stick and two. Everything stops first: the car must not
     *  be left driving on an order given by a stick that is about to vanish. */
    fun toggleStickMode() {
        releaseAll()
        _singleStick.value = !_singleStick.value
        preferences.edit().putBoolean(SINGLE_STICK_KEY, _singleStick.value).apply()
    }

    /* ----------------------------- Connection ----------------------------- */

    fun toggleConnection() {
        when (_state.value) {
            LinkState.CONNECTED -> disconnect()
            LinkState.SCANNING, LinkState.CONNECTING -> cancelConnection()
            LinkState.IDLE -> startScan()
        }
    }

    private fun disconnect() {
        /* Stop the wheels before dropping the link, instead of leaving the car
           rolling until its watchdog fires. */
        releaseAll()
        viewModelScope.launch {
            delay(200)   // let the stop go out
            link.disconnect()
            onDisconnected()
        }
    }

    private fun cancelConnection() {
        scanJob?.cancel()
        connectJob?.cancel()
        _picking.value = false
        _state.value = LinkState.IDLE
        statusLock = false
        setStatus("Scan cancelled")
    }

    fun startScan() {
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Turn on Bluetooth on this device.", isError = true)
            log("ERROR: Bluetooth off or unavailable")
            return
        }

        _devices.value = emptyList()
        _picking.value = true
        _state.value = LinkState.SCANNING
        setStatus("Looking for your micro:bit...")

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            link.scan(adapter)
                .catch { error ->
                    log("ERROR while scanning: ${describe(error)}")
                    setStatus("Scan failed — see the log", isError = true)
                    _picking.value = false
                    _state.value = LinkState.IDLE
                }
                .collect { found -> _devices.value = found }
        }
    }

    fun dismissPicker() {
        scanJob?.cancel()
        _picking.value = false
        if (_state.value == LinkState.SCANNING) {
            _state.value = LinkState.IDLE
            setStatus("No micro:bit chosen.")
            log("selection cancelled")
        }
    }

    fun choose(found: FoundDevice) {
        scanJob?.cancel()
        _picking.value = false
        connect(found.device, found.name)
    }

    private fun connect(device: BluetoothDevice, name: String) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.value = LinkState.CONNECTING
            statusLock = true
            setStatus("Connecting...")
            log("device: $name")

            try {
                link.connect(device)
                statusLock = false
                _state.value = LinkState.CONNECTED
                /* The pump opens with an "S" so the car is known to be stopped; keep
                   that off the status line so the connection message survives. */
                lastReported = Protocol.STOP
                lastSentMotion = null
                desiredMotion = Protocol.STOP
                setStatus("Connected to $name")
                log("connected")
                wake.trySend(Unit)
            } catch (error: Exception) {
                statusLock = false
                link.disconnect()
                reset()
                log("ERROR connecting: ${describe(error)}")
                setStatus("Could not connect — see the log", isError = true)
                _showLog.value = true
            }
        }
    }

    private fun onDisconnected() {
        if (_state.value == LinkState.IDLE) return
        reset()
        setStatus("Bluetooth disconnected")
    }

    private fun reset() {
        clearHeld()               // silently: there is no link left to send a stop over
        actionQueue.clear()
        desiredMotion = Protocol.STOP
        lastSentMotion = null     // the next connection must resend the state
        lastReported = ""
        statusLock = false
        _state.value = LinkState.IDLE
    }

    /* ------------------------------- Sending ------------------------------- */

    private fun setMotion(command: String) {
        desiredMotion = command
        wake.trySend(Unit)
    }

    private fun sendAction(command: String) {
        actionQueue.addLast(command)
        wake.trySend(Unit)
    }

    /** What, if anything, still needs to go out. */
    private fun nextPayload(): String? {
        actionQueue.removeFirstOrNull()?.let { return it }

        if (desiredMotion != lastSentMotion) return desiredMotion

        /* Moving: keep the watchdog fed. Stopped: nothing to say. */
        if (desiredMotion != Protocol.STOP &&
            SystemClock.elapsedRealtime() - lastMotionAt >= Protocol.HEARTBEAT_MS
        ) {
            return desiredMotion
        }

        return null
    }

    /**
     * The single writer. It wakes on a press and on its own every PUMP_MS, which
     * covers both the heartbeat and the retry after a failed write.
     */
    private suspend fun pumpLoop() {
        while (true) {
            if (!link.isReady) {
                if (desiredMotion != Protocol.STOP || actionQueue.isNotEmpty()) {
                    actionQueue.clear()
                    desiredMotion = Protocol.STOP
                    if (_state.value == LinkState.IDLE) setStatus("Connect the micro:bit first.", isError = true)
                }
                waitForWork()
                continue
            }

            val command = nextPayload()
            if (command == null) {
                waitForWork()
                continue
            }

            try {
                link.write((command + "\n").toByteArray())
            } catch (error: Exception) {
                /* Leave lastSentMotion alone so the next tick retries. A failed
                   stop is the dangerous one, and it will be retried in ~100ms. */
                log("ERROR sending $command: ${describe(error)}")
                setStatus("Send failed — see the log", isError = true)
                waitForWork()
                continue
            }

            if (Protocol.isMotion(command)) {
                lastSentMotion = command
                lastMotionAt = SystemClock.elapsedRealtime()
            }

            report(command)
        }
    }

    private suspend fun waitForWork() {
        withTimeoutOrNull(Protocol.PUMP_MS) { wake.receive() }
    }

    private fun report(command: String) {
        if (command == lastReported) return   // don't log every heartbeat
        lastReported = command

        log("sent: $command")
        if (statusLock) return

        when {
            command == Protocol.STOP -> setStatus("Stopped")
            Protocol.isMotion(command) -> {
                val parts = command.split(",")
                setStatus("Direction ${parts.getOrElse(0) { "?" }}  ·  Speed ${parts.getOrElse(1) { "?" }}")
            }
            else -> setStatus("→ $command")
        }
    }

    /* --------------------------- Press handling --------------------------- */

    fun press(command: Command) {
        if (!command.isDirection) {
            sendAction(command.name)
            flash(command)
            return
        }

        if (command in held) return
        held = held + command
        steer()
    }

    fun release(command: Command) {
        if (command !in held) return
        held = held - command
        steer()
    }

    /** Leaving the app or losing focus must not leave the car with a standing
     *  order to drive: a stick gets no release event when the app goes away, so
     *  both are centred here rather than waiting for the watchdog. */
    fun releaseAll() {
        if (held.isEmpty() && _left.value.isCentred && _right.value.isCentred) return
        centreSticks()
        setMotion(Protocol.STOP)
    }

    /** Forget every held key without sending anything (used when the link is
     *  already gone). The micro:bit's own watchdog stops the car in that case. */
    private fun clearHeld() {
        centreSticks()
    }

    private fun centreSticks() {
        held = emptySet()
        _left.value = Stick()
        _right.value = Stick()
        _readout.value = Protocol.STOP
    }

    private fun flash(command: Command) {
        viewModelScope.launch {
            _flashing.value = _flashing.value + command
            delay(120)
            _flashing.value = _flashing.value - command
        }
    }

    override fun onCleared() {
        link.disconnect()
        super.onCleared()
    }
}
