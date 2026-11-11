package app.batterycommander.transport

import kotlinx.coroutines.flow.Flow

/**
 * A connected GATT link to one battery, reduced to what a BMS driver needs.
 * Android and Linux (BlueZ) provide implementations; connecting, discovery and the clean
 * disconnect required by docs/architecture/ble-connection-management.md are their job.
 */
interface BleLink {
    /** Writes to the driver's write characteristic. */
    suspend fun write(data: ByteArray)

    /**
     * Notifications from the driver's notify characteristic. Must be hot (e.g. a SharedFlow):
     * chunks that arrive while nobody is collecting are dropped.
     */
    val notifications: Flow<ByteArray>
}
