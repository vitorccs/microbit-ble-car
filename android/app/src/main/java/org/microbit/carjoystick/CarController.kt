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

/**
 * The controller owns every piece of state the UI reads and is the only place
 * that decides what goes on the wire. It runs entirely on the main dispatcher,
 * so the BLE callbacks bounce back here before touching anything.
 */
class CarController(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>()

    private val _state = MutableStateFlow(LinkState.IDLE)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _status = MutableStateFlow(Status("Bluetooth desconectado"))
    val status: StateFlow<Status> = _status.asStateFlow()

    /* ---- Where the car is being steered from ----
       Two sources, and the stick wins whenever it is off centre: letting go of
       it must hand the car back to a key that is still down, rather than
       stopping it. Only the stick is shown, because only it has a readout. */
    private val _stick = MutableStateFlow(Stick())
    val stick: StateFlow<Stick> = _stick.asStateFlow()

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
        onNotify = { text -> viewModelScope.launch { log("micro:bit disse: ${text.trim()}") } },
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
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("pt-BR")).format(Date())
        _logLines.value = (_logLines.value + "$stamp  $line").takeLast(LOG_LIMIT)
        Log.d("car", line)
    }

    fun toggleLog() {
        _showLog.value = !_showLog.value
    }

    /* ------------------------------ Steering ------------------------------ */

    /** Called by the on-screen stick on every move, and on its release. */
    fun onStickMoved(direction: String, speed: Int) {
        val moved = Stick(direction, speed)
        if (moved == _stick.value) return
        _stick.value = moved
        setMotion(motionCommand())
    }

    /** The stick has the wheel while it is off centre; the keys take over only
     *  once it is home, so letting go of the stick hands a still-held key back
     *  its direction instead of stopping the car. */
    private fun motionCommand(): String {
        val stick = _stick.value
        if (!stick.isCentred) return Protocol.motion(stick.direction, stick.speed)
        return Protocol.motion(Protocol.directionFor(held), Protocol.KEY_SPEED)
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
        setStatus("Busca cancelada")
    }

    fun startScan() {
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Ligue o Bluetooth do aparelho.", isError = true)
            log("ERRO: Bluetooth desligado ou indisponível")
            return
        }

        _devices.value = emptyList()
        _picking.value = true
        _state.value = LinkState.SCANNING
        setStatus("Procurando o seu micro:bit...")

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            link.scan(adapter)
                .catch { error ->
                    log("ERRO na busca: ${describe(error)}")
                    setStatus("Falha ao buscar — veja o log", isError = true)
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
            setStatus("Nenhum micro:bit escolhido.")
            log("seleção cancelada")
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
            setStatus("Conectando...")
            log("dispositivo: $name")

            try {
                link.connect(device)
                statusLock = false
                _state.value = LinkState.CONNECTED
                /* The pump opens with an "S" so the car is known to be stopped; keep
                   that off the status line so the connection message survives. */
                lastReported = Protocol.STOP
                lastSentMotion = null
                desiredMotion = Protocol.STOP
                setStatus("Conectado a $name")
                log("conectado")
                wake.trySend(Unit)
            } catch (error: Exception) {
                statusLock = false
                link.disconnect()
                reset()
                log("ERRO ao conectar: ${describe(error)}")
                setStatus("Falha ao conectar — veja o log", isError = true)
                _showLog.value = true
            }
        }
    }

    private fun onDisconnected() {
        if (_state.value == LinkState.IDLE) return
        reset()
        setStatus("Bluetooth desconectado")
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
                    if (_state.value == LinkState.IDLE) setStatus("Conecte o micro:bit primeiro.", isError = true)
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
                log("ERRO ao enviar $command: ${describe(error)}")
                setStatus("Erro ao enviar — veja o log", isError = true)
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

        log("enviado: $command")
        if (statusLock) return

        when {
            command == Protocol.STOP -> setStatus("Parado")
            Protocol.isMotion(command) -> {
                val parts = command.split(",")
                setStatus("Direção ${parts.getOrElse(0) { "?" }}  ·  Velocidade ${parts.getOrElse(1) { "?" }}")
            }
            command in Protocol.UNHANDLED ->
                setStatus("$command enviado (o micro:bit ignora este comando)")
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
        setMotion(motionCommand())
    }

    fun release(command: Command) {
        if (command !in held) return
        held = held - command
        setMotion(motionCommand())
    }

    /** Leaving the app or losing focus must not leave the car with a standing
     *  order to drive: the stick gets no release event when the app goes away,
     *  so it is centred here rather than waiting for the watchdog. */
    fun releaseAll() {
        if (held.isEmpty() && _stick.value.isCentred) return
        held = emptySet()
        _stick.value = Stick()
        setMotion(Protocol.STOP)
    }

    /** Forget every held key without sending anything (used when the link is
     *  already gone). The micro:bit's own watchdog stops the car in that case. */
    private fun clearHeld() {
        held = emptySet()
        _stick.value = Stick()
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
