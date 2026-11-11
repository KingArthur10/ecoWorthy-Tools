@file:Suppress("FunctionName") // D-Bus method names must match BlueZ exactly.

package app.batterycommander.hub.bluez

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

// Minimal BlueZ D-Bus API surface (see BlueZ doc/org.bluez.*.rst). Only what the hub uses.

internal const val BLUEZ = "org.bluez"
internal const val ADAPTER_IFACE = "org.bluez.Adapter1"
internal const val DEVICE_IFACE = "org.bluez.Device1"
internal const val GATT_CHAR_IFACE = "org.bluez.GattCharacteristic1"

@DBusInterfaceName(ADAPTER_IFACE)
internal interface Adapter1 : DBusInterface {
    fun StartDiscovery()
    fun StopDiscovery()
    fun SetDiscoveryFilter(filter: Map<String, Variant<*>>)
}

@DBusInterfaceName(DEVICE_IFACE)
internal interface Device1 : DBusInterface {
    fun Connect()
    fun Disconnect()
}

@DBusInterfaceName(GATT_CHAR_IFACE)
internal interface GattCharacteristic1 : DBusInterface {
    fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>)
    fun StartNotify()
    fun StopNotify()
}
