package app.batterycommander.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import app.batterycommander.transport.BleLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class ScanHit(val mac: String, val name: String?, val rssiDbm: Int)

class BleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Android BLE entry point. Callers must hold BLUETOOTH_SCAN / BLUETOOTH_CONNECT
 * (see [hasPermissions]); the UI requests them before anything here runs.
 */
@SuppressLint("MissingPermission")
class AndroidBle(private val context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun hasPermissions(): Boolean = PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** Scans for devices advertising [serviceUuid] until the collector cancels. */
    fun scan(serviceUuid: String): Flow<ScanHit> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: throw BleException("Bluetooth is off")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(ScanHit(result.device.address, result.scanRecord?.deviceName ?: result.device.name, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException("Scan failed (code $errorCode)"))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(serviceUuid)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * Connects, discovers services, and enables notifications on [notifyUuid]. Every failed
     * attempt is fully disconnected and closed before the next one, so a failure never leaves the
     * battery held (docs/research/spike-bluez-jvm.md, finding 1).
     */
    suspend fun connect(
        mac: String,
        serviceUuid: String,
        writeUuid: String,
        notifyUuid: String,
        attempts: Int = 3,
    ): AndroidBleLink {
        val bt = adapter ?: throw BleException("No Bluetooth adapter")
        if (!bt.isEnabled) throw BleException("Bluetooth is off")
        val device = bt.getRemoteDevice(mac)
        var lastError: Exception? = null
        repeat(attempts) {
            try {
                return AndroidBleLink.open(context, device, UUID.fromString(serviceUuid), UUID.fromString(writeUuid), UUID.fromString(notifyUuid))
            } catch (e: Exception) {
                // Status 133 (GATT_ERROR) on the first try is common on Android; a retry usually works.
                lastError = e
                delay(1.seconds)
            }
        }
        throw BleException("Could not connect to $mac: ${lastError?.message}", lastError)
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}

/** An open GATT session to one battery. Always [close] it. */
@SuppressLint("MissingPermission")
class AndroidBleLink private constructor(
    private val gatt: BluetoothGatt,
    private val callback: Callback,
    private val writeChar: BluetoothGattCharacteristic,
    /** Signal strength read right after connecting, in dBm. */
    val rssiDbm: Int?,
) : BleLink {
    override val notifications: SharedFlow<ByteArray> = callback.notifications
    private val gattOps = Mutex()

    override suspend fun write(data: ByteArray) = gattOps.withLock {
        repeat(5) {
            val done = CompletableDeferred<Int>().also { callback.writeDone = it }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(writeChar, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                writeChar.run {
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    value = data
                    gatt.writeCharacteristic(this)
                }
            }
            if (started) {
                // For no-response writes this fires once the write is queued; don't fail if it never does.
                withTimeoutOrNull(1.seconds) { done.await() }
                return@withLock
            }
            delay(50.milliseconds) // stack busy with a previous operation
        }
        throw BleException("Write rejected by the Bluetooth stack")
    }

    /** Disconnects, waits for confirmation (up to 3 s), and releases the GATT client. */
    suspend fun close() = withContext(NonCancellable) { shutdown(gatt, callback) }

    internal class Callback : BluetoothGattCallback() {
        data class ConnState(val state: Int, val status: Int)

        val connection = MutableStateFlow(ConnState(BluetoothProfile.STATE_DISCONNECTED, BluetoothGatt.GATT_SUCCESS))
        val servicesDiscovered = CompletableDeferred<Int>()
        val descriptorWritten = CompletableDeferred<Int>()
        val rssi = CompletableDeferred<Int?>()
        val notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)

        @Volatile
        var writeDone: CompletableDeferred<Int>? = null

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            connection.value = ConnState(newState, status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            servicesDiscovered.complete(status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWritten.complete(status)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            this.rssi.complete(if (status == BluetoothGatt.GATT_SUCCESS) rssi else null)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.tryEmit(value.copyOf())
        }

        @Deprecated("Pre-Android 13 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) notifications.tryEmit(characteristic.value.copyOf())
        }
    }

    internal companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        suspend fun open(
            context: Context,
            device: BluetoothDevice,
            serviceUuid: UUID,
            writeUuid: UUID,
            notifyUuid: UUID,
        ): AndroidBleLink {
            val callback = Callback()
            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw BleException("connectGatt returned null")
            try {
                val conn = withTimeout(20.seconds) {
                    callback.connection.first {
                        it.state == BluetoothProfile.STATE_CONNECTED || it.status != BluetoothGatt.GATT_SUCCESS
                    }
                }
                if (conn.state != BluetoothProfile.STATE_CONNECTED) throw BleException("Connection failed (status ${conn.status})")

                if (!gatt.discoverServices()) throw BleException("Service discovery did not start")
                val discovered = withTimeout(10.seconds) { callback.servicesDiscovered.await() }
                if (discovered != BluetoothGatt.GATT_SUCCESS) throw BleException("Service discovery failed (status $discovered)")

                val service = gatt.getService(serviceUuid) ?: throw BleException("Service $serviceUuid not found")
                val notifyChar = service.getCharacteristic(notifyUuid) ?: throw BleException("Characteristic $notifyUuid not found")
                val writeChar = service.getCharacteristic(writeUuid) ?: throw BleException("Characteristic $writeUuid not found")

                // Notifications must be live before the first request, or its reply is lost.
                if (!gatt.setCharacteristicNotification(notifyChar, true)) throw BleException("Could not enable notifications")
                val cccd = notifyChar.getDescriptor(CCCD) ?: throw BleException("Notify characteristic has no CCCD")
                val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val writeStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.run { value = enable; gatt.writeDescriptor(this) }
                }
                if (!writeStarted) throw BleException("Could not write CCCD")
                val cccdStatus = withTimeout(5.seconds) { callback.descriptorWritten.await() }
                if (cccdStatus != BluetoothGatt.GATT_SUCCESS) throw BleException("Enabling notifications failed (status $cccdStatus)")

                val rssi = if (gatt.readRemoteRssi()) withTimeoutOrNull(2.seconds) { callback.rssi.await() } else null
                return AndroidBleLink(gatt, callback, writeChar, rssi)
            } catch (e: Exception) {
                withContext(NonCancellable) { shutdown(gatt, callback) }
                throw e
            }
        }

        suspend fun shutdown(gatt: BluetoothGatt, callback: Callback) {
            runCatching { gatt.disconnect() }
            withTimeoutOrNull(3.seconds) {
                callback.connection.first { it.state == BluetoothProfile.STATE_DISCONNECTED }
            }
            runCatching { gatt.close() }
        }
    }
}
