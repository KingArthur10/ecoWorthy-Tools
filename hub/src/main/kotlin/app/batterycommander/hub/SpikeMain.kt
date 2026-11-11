package app.batterycommander.hub

import app.batterycommander.hub.bluez.BluezBle
import app.batterycommander.protocol.jbd.JbdClient
import app.batterycommander.protocol.jbd.JbdGatt
import kotlinx.coroutines.runBlocking
import kotlin.time.TimeSource

/**
 * BlueZ spike: read-only poll of one or more JBD batteries, the way a hub polling cycle would.
 *
 * Usage: hub [--cycles N] MAC [MAC...]
 */
fun main(args: Array<String>) = runBlocking {
    var cycles = 1
    val macs = mutableListOf<String>()
    val it = args.iterator()
    while (it.hasNext()) {
        when (val a = it.next()) {
            "--cycles" -> cycles = it.next().toInt()
            else -> macs += a
        }
    }
    require(macs.isNotEmpty()) { "Usage: hub [--cycles N] MAC [MAC...]" }

    var ok = 0
    var failed = 0
    BluezBle().use { ble ->
        repeat(cycles) { cycle ->
            for (mac in macs) {
                val start = TimeSource.Monotonic.markNow()
                try {
                    val link = ble.connect(mac, writeUuid = JbdGatt.WRITE, notifyUuid = JbdGatt.NOTIFY)
                    val connected = start.elapsedNow()
                    var readMs = 0L
                    var closeMs = 0L
                    val snapshot = try {
                        JbdClient(link).readSnapshot().also { readMs = (start.elapsedNow() - connected).inWholeMilliseconds }
                    } finally {
                        val closeStart = TimeSource.Monotonic.markNow()
                        val clean = link.close()
                        closeMs = closeStart.elapsedNow().inWholeMilliseconds
                        if (!clean) println("  WARNING: disconnect of $mac not confirmed")
                    }
                    val b = snapshot.basic
                    println(
                        "cycle ${cycle + 1} $mac ${snapshot.model} rssi=${link.rssi}dBm " +
                            "soc=${b.stateOfChargePct}% ${b.packVoltageMv / 1000.0}V ${b.currentMa / 1000.0}A " +
                            "cells=${snapshot.cellVoltagesMv} temps=${b.temperaturesDeciC.map { t -> t / 10.0 }}C " +
                            "fet(c/d)=${b.chargeFetOn}/${b.dischargeFetOn} " +
                            "scan+connect=${connected.inWholeMilliseconds}ms read=${readMs}ms close=${closeMs}ms total=${start.elapsedNow().inWholeMilliseconds}ms",
                    )
                    ok++
                } catch (e: Exception) {
                    println("cycle ${cycle + 1} $mac FAILED after ${start.elapsedNow().inWholeMilliseconds}ms: $e")
                    failed++
                }
            }
        }
    }
    println("done: $ok ok, $failed failed")
    // Leave nothing connected: report any battery BlueZ still holds.
    BluezBle().use { ble -> macs.filter { ble.isConnected(it) }.forEach { println("STILL CONNECTED: $it") } }
}
