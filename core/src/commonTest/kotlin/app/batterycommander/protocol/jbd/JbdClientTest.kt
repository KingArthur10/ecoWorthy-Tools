package app.batterycommander.protocol.jbd

import app.batterycommander.transport.BleLink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Replays captured frames in 20-byte notifications, like the real BLE module. */
private class FakeJbdLink(
    capture: Captures.Capture,
    /** Return null to drop the reply, or different bytes to corrupt it. Called once per request. */
    private val tamper: (attempt: Int, command: Int, frame: ByteArray) -> ByteArray? = { _, _, f -> f },
) : BleLink {
    private val replies = mapOf(
        0x03 to capture.basic.hexToBytes(),
        0x04 to capture.cells.hexToBytes(),
        0x05 to capture.hwver.hexToBytes(),
    )
    private val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val writes = mutableListOf<ByteArray>()

    override val notifications: SharedFlow<ByteArray> = flow

    override suspend fun write(data: ByteArray) {
        writes += data
        val command = data[2].toInt() and 0xFF
        val reply = tamper(writes.size, command, replies.getValue(command)) ?: return
        reply.asList().chunked(20).forEach { flow.emit(it.toByteArray()) }
    }
}

class JbdClientTest {
    @Test
    fun readsFullSnapshot() = runTest {
        val link = FakeJbdLink(Captures.byMac("A5:C2:37:2E:A2:52"))
        val snapshot = JbdClient(link).readSnapshot()

        assertEquals(81, snapshot.basic.stateOfChargePct)
        assertEquals(165000, snapshot.basic.nominalCapacityMah)
        assertEquals(listOf(3335, 3336, 3336, 3336), snapshot.cellVoltagesMv)
        assertEquals("DP04S007L4S120A", snapshot.model)
        assertEquals(3, link.writes.size)
    }

    @Test
    fun sendsOnlyReadRequests() = runTest {
        val link = FakeJbdLink(Captures.all[0])
        JbdClient(link).readSnapshot()
        link.writes.forEach { assertEquals(JbdFrame.READ, it[1].toInt() and 0xFF) }
    }

    @Test
    fun retriesAfterDroppedReply() = runTest {
        val link = FakeJbdLink(Captures.all[0]) { attempt, _, frame -> if (attempt == 1) null else frame }
        val response = JbdClient(link).read(JbdReadCommand.BASIC_INFO)
        assertEquals(0x03, response.command)
        assertEquals(2, link.writes.size)
    }

    @Test
    fun retriesAfterCorruptReply() = runTest {
        val link = FakeJbdLink(Captures.all[0]) { attempt, _, frame ->
            if (attempt == 1) frame.copyOf().also { it[6] = (it[6] + 1).toByte() } else frame
        }
        val response = JbdClient(link).read(JbdReadCommand.CELL_VOLTAGES)
        assertContentEquals(Captures.all[0].cells.hexToBytes().copyOfRange(4, 12), response.payload)
        assertEquals(2, link.writes.size)
    }

    @Test
    fun ignoresRepliesToOtherCommands() = runTest {
        // A late 0x03 reply arriving while we wait for 0x04 must not be taken as the answer.
        val stale = Captures.all[0].basic.hexToBytes()
        val link = FakeJbdLink(Captures.all[0]) { _, _, frame -> stale + frame }
        val response = JbdClient(link).read(JbdReadCommand.CELL_VOLTAGES)
        assertEquals(0x04, response.command)
    }

    @Test
    fun failsAfterAllAttemptsTimeOut() = runTest {
        val link = FakeJbdLink(Captures.all[0]) { _, _, _ -> null }
        assertFailsWith<JbdProtocolException> { JbdClient(link, attempts = 3).read(JbdReadCommand.BASIC_INFO) }
        assertEquals(3, link.writes.size)
    }
}
