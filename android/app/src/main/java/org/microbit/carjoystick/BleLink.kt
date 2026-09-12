package org.microbit.carjoystick

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class BleException(message: String) : Exception(message)

data class FoundDevice(val device: BluetoothDevice, val name: String, val rssi: Int)

/**
 * One GATT connection to a micro:bit, with the UART service resolved and a
 * serialized writer: Android allows a single outstanding GATT operation, and
 * firing a second one before the first completes silently drops it.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onNotify: (String) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null

    private val writeMutex = Mutex()
    private var pendingWrite: CompletableDeferred<Int>? = null
    private var pendingConnect: CompletableDeferred<Unit>? = null
    private var pendingDiscovery: CompletableDeferred<Unit>? = null
    private var pendingDescriptor: CompletableDeferred<Int>? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    /** True once the UART RX characteristic is in hand and writable. */
    val isReady: Boolean get() = isConnected && rx != null

    /* ------------------------------- Scanning ------------------------------- */

    companion object {
        /** The micro:bit does not advertise its UART service, so filter by name. */
        private val NAME_PREFIXES = listOf("BBC micro:bit", "micro:bit")

        fun matchesMicrobit(name: String?): Boolean =
            name != null && NAME_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }

    /**
     * Emits the growing list of micro:bits in range. Scanning cannot filter on a
     * name prefix, so everything is scanned and matched here; devices that
     * advertise the UART service are let through too.
     */
    fun scan(adapter: BluetoothAdapter): Flow<List<FoundDevice>> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner
            ?: throw BleException("Bluetooth desligado — ligue o Bluetooth e tente de novo.")

        val found = LinkedHashMap<String, FoundDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val advertised = result.scanRecord?.deviceName
                val name = result.device.name ?: advertised
                val hasUart = result.scanRecord?.serviceUuids
                    ?.any { it.uuid == Uart.SERVICE } == true

                if (!matchesMicrobit(name) && !hasUart) return

                val entry = FoundDevice(result.device, name ?: "(sem nome)", result.rssi)
                val key = result.device.address
                if (found.put(key, entry) == null) log("encontrado: ${entry.name} ($key)")
                trySend(found.values.toList())
            }

            override fun onScanFailed(errorCode: Int) {
                log("ERROR while scanning: code $errorCode")
                close(BleException("Device scan failed (code $errorCode)"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, callback)
        log("buscando micro:bit...")

        awaitClose {
            runCatching { scanner.stopScan(callback) }
            log("busca encerrada")
        }
    }

    /* ------------------------------ Connection ------------------------------ */

    suspend fun connect(device: BluetoothDevice) {
        disconnect()

        val connected = CompletableDeferred<Unit>()
        pendingConnect = connected

        /* API 37 replaced this with connectGatt(BluetoothGattConnectionSettings, ...),
           which does not exist on the releases this app still supports. */
        @Suppress("DEPRECATION")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

        withTimeout(15_000) { connected.await() }

        val discovered = CompletableDeferred<Unit>()
        pendingDiscovery = discovered
        if (gatt?.discoverServices() != true) throw BleException("Could not list the services.")
        withTimeout(15_000) { discovered.await() }

        val service = gatt?.getService(Uart.SERVICE)
            ?: throw BleException("UART service not found — is the right program on the micro:bit?")

        val characteristic = service.getCharacteristic(Uart.RX)
            ?: run {
                describeService(service)
                throw BleException("Write characteristic ${Uart.RX} not found — see the log")
            }

        val canWrite = characteristic.properties and
            (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        log(
            "write properties: write=" +
                (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) +
                " writeWithoutResponse=" +
                (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
        )

        /* The micro:bit's RX characteristic always advertises write. If it does
           not, we are talking to the wrong characteristic, so dump the whole
           service instead of failing silently on the first button press. */
        if (!canWrite) {
            describeService(service)
            throw BleException("UART service unreachable — see the log")
        }

        rx = characteristic
        subscribeNotifications(service)
    }

    /** Listening to the notify characteristic is not needed to drive the
     *  car, but anything arriving here proves the link really works. */
    private suspend fun subscribeNotifications(service: BluetoothGattService) {
        try {
            val tx = service.getCharacteristic(Uart.TX) ?: throw BleException("no notify characteristic")
            val active = gatt?.setCharacteristicNotification(tx, true) == true
            if (!active) throw BleException("setCharacteristicNotification recusado")

            val cccd = tx.getDescriptor(Uart.CCCD) ?: throw BleException("sem descritor CCCD")
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            val done = CompletableDeferred<Int>()
            pendingDescriptor = done

            @Suppress("DEPRECATION")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeDescriptor(cccd, enable) == BluetoothStatusCodes_SUCCESS
            } else {
                cccd.value = enable
                gatt?.writeDescriptor(cccd) == true
            }
            if (!started) throw BleException("escrita do CCCD recusada")

            withTimeout(5_000) { done.await() }
            log("notifications on")
        } catch (error: Exception) {
            pendingDescriptor = null
            log("no notifications (${describe(error)}) — does not stop the controls")
        }
    }

    /** Dumps every characteristic the UART service has, which is what
     *  distinguishes a stale cache from a genuinely different service. */
    private fun describeService(service: BluetoothGattService) {
        log("characteristics found on the UART service: ${service.characteristics.size}")
        service.characteristics.forEach { characteristic ->
            log("  ${characteristic.uuid} → ${propertyNames(characteristic.properties)}")
        }
    }

    private fun propertyNames(properties: Int): String {
        val names = buildList {
            if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeWithoutResponse")
            if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        }
        return if (names.isEmpty()) "(nenhuma propriedade)" else names.joinToString(", ")
    }

    fun disconnect() {
        val current = gatt ?: return
        gatt = null
        rx = null
        isConnected = false
        runCatching { current.disconnect() }
        runCatching { current.close() }
    }

    /* -------------------------------- Writing -------------------------------- */

    /**
     * Write-without-response is faster, so it is used when the characteristic
     * declares it; otherwise the acknowledged write is used. Either way exactly
     * one write is on the wire at a time.
     */
    suspend fun write(payload: ByteArray) = writeMutex.withLock {
        val characteristic = rx ?: throw BleException("not connected")
        val activeGatt = gatt ?: throw BleException("not connected")

        val noResponse = characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val done = CompletableDeferred<Int>()
        pendingWrite = done

        @Suppress("DEPRECATION")
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activeGatt.writeCharacteristic(characteristic, payload, type) == BluetoothStatusCodes_SUCCESS
        } else {
            characteristic.writeType = type
            characteristic.value = payload
            activeGatt.writeCharacteristic(characteristic)
        }

        if (!started) {
            pendingWrite = null
            throw BleException("escrita recusada pela pilha Bluetooth")
        }

        val status = withTimeout(4_000) { done.await() }
        if (status != BluetoothGatt.GATT_SUCCESS) throw BleException("GATT status $status")
    }

    /* ------------------------------- Callbacks ------------------------------- */

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                pendingConnect?.complete(Unit)
                pendingConnect = null
                return
            }

            if (newState != BluetoothProfile.STATE_DISCONNECTED) return

            isConnected = false
            rx = null

            val failure = BleException("desconectado (status $status)")
            pendingConnect?.completeExceptionally(failure)
            pendingDiscovery?.completeExceptionally(failure)
            pendingDescriptor?.completeExceptionally(failure)
            pendingWrite?.completeExceptionally(failure)
            pendingConnect = null
            pendingDiscovery = null
            pendingDescriptor = null
            pendingWrite = null

            runCatching { g.close() }
            if (gatt === g) gatt = null

            onDisconnected()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingDiscovery?.complete(Unit)
            } else {
                pendingDiscovery?.completeExceptionally(BleException("service discovery failed (status $status)"))
            }
            pendingDiscovery = null
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            pendingWrite?.complete(status)
            pendingWrite = null
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingDescriptor?.complete(status)
            pendingDescriptor = null
        }

        /* API 33+ delivers the value as a parameter; older releases read it off
           the characteristic, which is why both overloads are implemented. */
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotify(String(value))
        }

        @Deprecated("Deprecated in API 33, still the only callback below it")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            characteristic.value?.let { onNotify(String(it)) }
        }
    }
}

/** BluetoothStatusCodes.SUCCESS, inlined so the file compiles below API 33. */
private const val BluetoothStatusCodes_SUCCESS = 0

fun describe(error: Throwable?): String {
    if (error == null) return "erro desconhecido"
    val name = error::class.simpleName ?: "Error"
    return "$name: ${error.message ?: error.toString()}"
}
