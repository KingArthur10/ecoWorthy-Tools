package app.batterycommander.hub.bluez

import app.batterycommander.domain.DiscoveredDevice
import app.batterycommander.transport.BleLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A connected GATT session to one battery over BlueZ. Create with [BluezBle.connect];
 * always [close] it: the battery accepts one client at a time, and some modules lock up after
 * an unclean disconnect (docs/hardware/ble-variants.md).
 */
class BluezBleLink internal constructor(
    private val bus: DBusConnection,
    private val devicePath: String,
    private val writePath: String,
    private val notifyPath: String,
    /** Signal strength seen during discovery, in dBm, if BlueZ reported one. */
    val rssi: Int?,
) : BleLink {
    private val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val notifications: SharedFlow<ByteArray> = flow

    private val writer = bus.getRemoteObject(BLUEZ, writePath, GattCharacteristic1::class.java)
    private val notifier = bus.getRemoteObject(BLUEZ, notifyPath, GattCharacteristic1::class.java)
    private var signalHandle: AutoCloseable? = null

    internal suspend fun startNotifications() {
        signalHandle = bus.addSigHandler(Properties.PropertiesChanged::class.java) { signal ->
            if (signal.path == notifyPath && signal.interfaceName == GATT_CHAR_IFACE) {
                signal.propertiesChanged["Value"]?.let { flow.tryEmit(it.toByteArray()) }
            }
        }
        notifier.StartNotify()
        // StartNotify can return before the subscription is live; a request sent too early
        // loses its reply and costs a full response timeout.
        if (!waitFor(3.seconds) { bus.property<Boolean>(notifyPath, GATT_CHAR_IFACE, "Notifying") == true }) {
            throw BluezException("Notifications did not start on $notifyPath")
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        // "command" = write without response, which is what the JBD write characteristic supports.
        writer.WriteValue(data, mapOf("type" to Variant("command")))
    }

    /**
     * Stops notifications and disconnects, then waits until BlueZ reports the device as
     * disconnected. Returns true if the disconnect was confirmed.
     */
    suspend fun close(timeout: Duration = 5.seconds): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { notifier.StopNotify() }
        runCatching { signalHandle?.close() }
        bus.disconnectAndWait(devicePath, timeout)
    }
}

/** Entry point for BLE on Linux via BlueZ's D-Bus API. */
class BluezBle(private val adapter: String = "hci0") : AutoCloseable {
    private val bus: DBusConnection = DBusConnectionBuilder.forSystemBus().build()
    private val adapterPath = "/org/bluez/$adapter"

    /**
     * Discovers the device, connects, resolves services, and subscribes to [notifyUuid].
     * Cleans up (disconnects) if any step after connecting fails.
     */
    suspend fun connect(
        mac: String,
        writeUuid: String,
        notifyUuid: String,
        scanTimeout: Duration = 15.seconds,
        connectAttempts: Int = 3,
    ): BluezBleLink = withContext(Dispatchers.IO) {
        val devicePath = "$adapterPath/dev_" + mac.uppercase().replace(':', '_')
        // A connection left over from a crashed run stops the battery advertising; clear it first.
        if (bus.property<Boolean>(devicePath, DEVICE_IFACE, "Connected") == true) bus.disconnectAndWait(devicePath)

        val rssi = discover(devicePath, scanTimeout)
        val device = bus.getRemoteObject(BLUEZ, devicePath, Device1::class.java)

        // Any failure from here on must end in a confirmed disconnect: BlueZ can complete a
        // connection in the background even after Connect() returned an error, and a held
        // connection locks every other client (phone, official app) out of the battery.
        try {
            var lastError: Exception? = null
            for (attempt in 1..connectAttempts) {
                try {
                    device.Connect()
                    lastError = null
                    break
                } catch (e: DBusExecutionException) {
                    // Typically "le-connection-abort-by-local" or "In Progress"; reset and retry.
                    lastError = e
                    bus.disconnectAndWait(devicePath)
                    delay(1.seconds)
                }
            }
            lastError?.let { throw BluezException("Connect to $mac failed after $connectAttempts attempts: ${it.message}", it) }

            if (!waitFor(10.seconds) { bus.property<Boolean>(devicePath, DEVICE_IFACE, "ServicesResolved") == true }) {
                throw BluezException("Services not resolved on $mac")
            }
            val chars = characteristics(devicePath)
            val writePath = chars[writeUuid.lowercase()] ?: throw BluezException("$mac has no characteristic $writeUuid")
            val notifyPath = chars[notifyUuid.lowercase()] ?: throw BluezException("$mac has no characteristic $notifyUuid")
            BluezBleLink(bus, devicePath, writePath, notifyPath, rssi).also { it.startNotifications() }
        } catch (e: Exception) {
            withContext(NonCancellable) { bus.disconnectAndWait(devicePath) }
            throw e
        }
    }

    /** Runs LE discovery until the device shows up with a fresh RSSI. Returns that RSSI. */
    private suspend fun discover(devicePath: String, timeout: Duration): Int? {
        val adapter = bus.getRemoteObject(BLUEZ, adapterPath, Adapter1::class.java)
        adapter.SetDiscoveryFilter(mapOf("Transport" to Variant("le"), "DuplicateData" to Variant(true)))
        val startedHere = runCatching { adapter.StartDiscovery() }.isSuccess // fails if already discovering
        try {
            var rssi: Int? = null
            val found = waitFor(timeout) {
                rssi = bus.property<Short>(devicePath, DEVICE_IFACE, "RSSI")?.toInt()
                rssi != null
            }
            if (!found) throw BluezException("Device $devicePath not seen within $timeout")
            return rssi
        } finally {
            // Discovery running during a connect makes BlueZ LE connects unreliable.
            if (startedHere) runCatching { adapter.StopDiscovery() }
        }
    }

    /**
     * LE discovery for [duration]; returns devices advertising [serviceUuid] that were heard during
     * this scan (BlueZ only reports RSSI for those), strongest first.
     */
    suspend fun scan(serviceUuid: String, duration: Duration): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val adapter = bus.getRemoteObject(BLUEZ, adapterPath, Adapter1::class.java)
        adapter.SetDiscoveryFilter(mapOf("Transport" to Variant("le"), "DuplicateData" to Variant(true)))
        val startedHere = runCatching { adapter.StartDiscovery() }.isSuccess
        val seen = mutableMapOf<String, DiscoveredDevice>()
        val uuid = serviceUuid.lowercase()
        try {
            val deadline = TimeSource.Monotonic.markNow() + duration
            while (!deadline.hasPassedNow()) {
                val om = bus.getRemoteObject(BLUEZ, "/", ObjectManager::class.java)
                for ((path, ifaces) in om.GetManagedObjects()) {
                    if (!path.path.startsWith("$adapterPath/dev_")) continue
                    val dev = ifaces[DEVICE_IFACE] ?: continue
                    val rssi = (dev["RSSI"]?.value as? Number)?.toInt() ?: continue
                    val uuids = when (val raw = dev["UUIDs"]?.value) {
                        is List<*> -> raw.map { it.toString().lowercase() }
                        is Array<*> -> raw.map { it.toString().lowercase() }
                        else -> emptyList()
                    }
                    if (uuid !in uuids) continue
                    val mac = dev["Address"]?.value as? String ?: continue
                    seen[mac] = DiscoveredDevice(mac, dev["Name"]?.value as? String, rssi)
                }
                delay(500.milliseconds)
            }
        } finally {
            if (startedHere) runCatching { adapter.StopDiscovery() }
        }
        seen.values.sortedByDescending { it.rssiDbm }
    }

    /** Map of lowercase characteristic UUID to object path, for one device. */
    private fun characteristics(devicePath: String): Map<String, String> {
        val om = bus.getRemoteObject(BLUEZ, "/", ObjectManager::class.java)
        return om.GetManagedObjects()
            .filterKeys { it.path.startsWith("$devicePath/") }
            .mapNotNull { (path: DBusPath, ifaces) ->
                val uuid = ifaces[GATT_CHAR_IFACE]?.get("UUID")?.value as? String
                uuid?.let { it.lowercase() to path.path }
            }
            .toMap()
    }

    fun isConnected(mac: String): Boolean =
        bus.property<Boolean>("$adapterPath/dev_" + mac.uppercase().replace(':', '_'), DEVICE_IFACE, "Connected") == true

    override fun close() = bus.close()
}

class BluezException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Disconnects (cancelling any pending connect) and waits until BlueZ reports it. True if confirmed. */
internal suspend fun DBusConnection.disconnectAndWait(devicePath: String, timeout: Duration = 5.seconds): Boolean {
    runCatching { getRemoteObject(BLUEZ, devicePath, Device1::class.java).Disconnect() }
    return waitFor(timeout) { property<Boolean>(devicePath, DEVICE_IFACE, "Connected") != true }
}

/** Reads a property, or null if the object or property doesn't exist (yet). */
@Suppress("UNCHECKED_CAST")
internal fun <T> DBusConnection.property(path: String, iface: String, name: String): T? = try {
    getRemoteObject(BLUEZ, path, Properties::class.java).Get<Any>(iface, name) as T
} catch (e: DBusExecutionException) {
    null
}

internal suspend fun waitFor(timeout: Duration, condition: () -> Boolean): Boolean {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    return withTimeoutOrNull(timeout + 1.seconds) {
        while (!condition()) {
            if (deadline.hasPassedNow()) return@withTimeoutOrNull false
            delay(100.milliseconds)
        }
        true
    } ?: false
}

/** BlueZ sends `ay` values as byte[]; tolerate a boxed list just in case. */
private fun Variant<*>.toByteArray(): ByteArray = when (val v = value) {
    is ByteArray -> v
    is List<*> -> ByteArray(v.size) { (v[it] as Number).toByte() }
    else -> error("Unexpected Value type ${v?.javaClass}")
}
