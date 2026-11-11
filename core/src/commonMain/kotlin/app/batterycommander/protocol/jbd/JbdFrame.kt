package app.batterycommander.protocol.jbd

/**
 * JBD (Jiabaida / Xiaoxiang) BMS framing. See docs/hardware/protocols/jbd.md.
 *
 * Request:  DD A5 <cmd> <len> <data…> <chk_hi> <chk_lo> 77
 * Response: DD <cmd> <status> <len> <data…> <chk_hi> <chk_lo> 77
 * Checksum: 0x10000 − sum(bytes from <cmd>/<status> through the end of <data>), truncated to 16 bits.
 */
object JbdFrame {
    const val START: Int = 0xDD
    const val END: Int = 0x77
    const val READ: Int = 0xA5

    /** Frame overhead: start, cmd, status/len, len, chk_hi, chk_lo, end. */
    const val OVERHEAD: Int = 7

    /**
     * Builds a read request. Only [JbdReadCommand] values are accepted, so this function cannot
     * produce a command that changes battery state.
     */
    fun readRequest(command: JbdReadCommand): ByteArray {
        val body = intArrayOf(command.code, 0x00)
        val chk = checksum(body.asIterable())
        return bytesOf(START, READ, command.code, 0x00, chk shr 8, chk and 0xFF, END)
    }

    fun checksum(bytes: Iterable<Int>): Int = (0x10000 - bytes.sumOf { it and 0xFF }) and 0xFFFF

    /**
     * Parses one complete response frame.
     *
     * @throws JbdProtocolException if the frame is malformed, has a bad checksum, or reports an error status.
     */
    fun parseResponse(frame: ByteArray): JbdResponse {
        if (frame.size < OVERHEAD) throw JbdProtocolException("Frame too short: ${frame.size} bytes")
        if (frame.u8(0) != START) throw JbdProtocolException("Bad start byte: ${frame.u8(0).hex()}")
        if (frame.u8(frame.size - 1) != END) throw JbdProtocolException("Bad end byte: ${frame.u8(frame.size - 1).hex()}")

        val length = frame.u8(3)
        if (frame.size != length + OVERHEAD) {
            throw JbdProtocolException("Length mismatch: header says $length data bytes, frame has ${frame.size - OVERHEAD}")
        }
        val expected = checksum((2 until 4 + length).map { frame.u8(it) })
        val actual = frame.u16(4 + length)
        if (expected != actual) throw JbdProtocolException("Checksum mismatch: expected ${expected.hex()}, got ${actual.hex()}")

        val status = frame.u8(2)
        if (status != 0) throw JbdProtocolException("BMS returned error status ${status.hex()} for command ${frame.u8(1).hex()}")

        return JbdResponse(command = frame.u8(1), payload = frame.copyOfRange(4, 4 + length))
    }
}

/** Read-only commands. Write commands deliberately live elsewhere (see docs/safety.md). */
enum class JbdReadCommand(val code: Int) {
    BASIC_INFO(0x03),
    CELL_VOLTAGES(0x04),
    HARDWARE_VERSION(0x05),
}

class JbdResponse(val command: Int, val payload: ByteArray)

class JbdProtocolException(message: String) : Exception(message)

internal fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.s16(i: Int): Int = u16(i).let { if (it and 0x8000 != 0) it - 0x10000 else it }

internal fun Int.hex(): String = "0x" + toString(16).uppercase().padStart(2, '0')
