package app.batterycommander.domain

import app.batterycommander.protocol.jbd.JbdSnapshot
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** A saved battery the app tracks and polls. See docs/product/features/device-management.md. */
@Serializable
data class Favorite(
    val mac: String,
    val nickname: String,
    /** Advertised BLE name, e.g. `DP04S007L4S100A`. Not unique: several batteries share it. */
    val bleName: String? = null,
    val protocol: Protocol = Protocol.JBD,
    val notes: String = "",
    /** [BatteryGroup.id] this battery belongs to, if any. */
    val groupId: String? = null,
)

@Serializable
enum class Protocol { JBD }

/**
 * One full read of a battery, flattened to protocol-neutral fields for caching, history, and UI.
 * Integer units as in the protocol layer: mV, mA, mAh, tenths of °C. Current is positive while charging.
 */
@Serializable
data class BatteryReading(
    val timestampMs: Long,
    val rssiDbm: Int? = null,
    val packVoltageMv: Int,
    val currentMa: Int,
    val stateOfChargePct: Int,
    val remainingCapacityMah: Int,
    val nominalCapacityMah: Int,
    val fullChargeCapacityMah: Int? = null,
    val cycles: Int,
    val cellVoltagesMv: List<Int>,
    val temperaturesDeciC: List<Int>,
    val chargeFetOn: Boolean,
    val dischargeFetOn: Boolean,
    val balancingMask: Long,
    val protections: List<String>,
    val productionDate: String,
    val firmwareVersion: String,
    val model: String,
) {
    val powerMw: Long get() = packVoltageMv.toLong() * currentMa / 1000
    val cellDeltaMv: Int? get() = if (cellVoltagesMv.isEmpty()) null else cellVoltagesMv.max() - cellVoltagesMv.min()

    val flow: PowerFlow
        get() = when {
            abs(currentMa) < IDLE_THRESHOLD_MA -> PowerFlow.IDLE
            currentMa > 0 -> PowerFlow.CHARGING
            else -> PowerFlow.DISCHARGING
        }

    companion object {
        /** Below this, current is treated as BMS measurement noise. */
        const val IDLE_THRESHOLD_MA = 100
    }
}

enum class PowerFlow { CHARGING, DISCHARGING, IDLE }

fun JbdSnapshot.toReading(timestampMs: Long, rssiDbm: Int?): BatteryReading = BatteryReading(
    timestampMs = timestampMs,
    rssiDbm = rssiDbm,
    packVoltageMv = basic.packVoltageMv,
    currentMa = basic.currentMa,
    stateOfChargePct = basic.stateOfChargePct,
    remainingCapacityMah = basic.remainingCapacityMah,
    nominalCapacityMah = basic.nominalCapacityMah,
    fullChargeCapacityMah = basic.fullChargeCapacityMah,
    cycles = basic.cycles,
    cellVoltagesMv = cellVoltagesMv,
    temperaturesDeciC = basic.temperaturesDeciC,
    chargeFetOn = basic.chargeFetOn,
    dischargeFetOn = basic.dischargeFetOn,
    balancingMask = basic.balancingMask,
    protections = basic.protections.map { it.name }.sorted(),
    productionDate = basic.productionDate.toString(),
    firmwareVersion = basic.softwareVersion,
    model = model,
)

/** Signal strength buckets for the UI's bars (0–4). Thresholds from the spike's observed range. */
fun signalBars(rssiDbm: Int?): Int = when {
    rssiDbm == null -> 0
    rssiDbm >= -65 -> 4
    rssiDbm >= -75 -> 3
    rssiDbm >= -85 -> 2
    rssiDbm >= -95 -> 1
    else -> 0
}

/** "just now", "5m ago", "3h ago", "2d ago". */
fun formatAge(nowMs: Long, thenMs: Long): String {
    val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}
