package app.batterycommander.protocol.jbd

/**
 * State-changing JBD commands, deliberately separate from [JbdFrame.readRequest] (docs/safety.md). Only MOSFET
 * control exists; parameter (EEPROM) writes and factory mode are not implemented.
 *
 * MOSFET control: `DD 5A E1 02 00 <v> <chk_hi> <chk_lo> 77`, where bit 0 of v = charge off and bit 1 = discharge
 * off. The value is absolute (both switches every time), so resending it is harmless. Reply: `DD E1 00 00 …`.
 * No factory mode is needed (esphome-jbd-bms does the same); the BMS reports the result as "MOSFET software lock"
 * (protection bit 12) and in the 0x03 status byte.
 */
object JbdWrite {
    const val WRITE: Int = 0x5A
    const val MOSFET_CONTROL: Int = 0xE1

    fun mosfetRequest(chargeOn: Boolean, dischargeOn: Boolean): ByteArray {
        val value = (if (chargeOn) 0 else 0x01) or (if (dischargeOn) 0 else 0x02)
        val chk = JbdFrame.checksum(listOf(MOSFET_CONTROL, 0x02, 0x00, value))
        return bytesOf(JbdFrame.START, WRITE, MOSFET_CONTROL, 0x02, 0x00, value, chk shr 8, chk and 0xFF, JbdFrame.END)
    }
}
