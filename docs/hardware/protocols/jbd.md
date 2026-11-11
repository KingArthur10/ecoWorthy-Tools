# JBD / Xiaoxiang protocol

**Verified against real hardware:** Reads verified 2026-11-10 (5 batteries, firmware 6.6). Writes not yet verified.

## Reference implementations
- aiobmsble (Python): https://pypi.org/project/aiobmsble/
- TBD

## Transport
- Service UUID: `0000ff00-0000-1000-8000-00805f9b34fb` (confirmed in advertisements, 2026-11-10)
- Notify characteristic: `0xFF01` (read, notify). **Verified**
- Write characteristic: `0xFF02` (read, write-without-response). **Verified**
- Other services seen: `1800`, `1801`, `180a` (standard), and
  `00010203-0405-0607-0809-0a0b0c0d1912`, which is **probably a Telink OTA/firmware service. Never write to it.**
- Responses can arrive split across several notifications. Collect them until the length matches and the last byte is `0x77`.

## Frame format
- Request: `DD A5 <cmd> <len> <data…> <chk_hi> <chk_lo> 77` for a read, or `DD 5A …` for a write
- Response: `DD <cmd> <status> <len> <data…> <chk_hi> <chk_lo> 77`. Status 0 means OK
- Checksum = `0x10000 − sum(bytes from status/cmd through the end of data)`, as 16 bits
- Read requests: `DD A5 03 00 FF FD 77`, `DD A5 04 00 FF FC 77`, `DD A5 05 00 FF FB 77`

## Read commands
| Command | Bytes | Response fields |
|---|---|---|
| 0x03 basic info | 34 bytes (fw 6.6) | See below |
| 0x04 cell voltages | 2 bytes per cell | u16, in mV |
| 0x05 hardware version | ASCII | for example `DP04S007L4S100A` |

### 0x03 basic info layout (big-endian; verified)
| Offset | Size | Field | Unit |
|---|---|---|---|
| 0 | u16 | Pack voltage | 10 mV |
| 2 | s16 | Current (+ charging) | 10 mA |
| 4 | u16 | Remaining capacity | 10 mAh |
| 6 | u16 | Nominal capacity | 10 mAh |
| 8 | u16 | Cycles | |
| 10 | u16 | Production date | bits: year−2000 (7), month (4), day (5) |
| 12 | u16 ×2 | Balance status (cells 1–16, then 17–32) | bitmask |
| 16 | u16 | Protection status | bitmask |
| 18 | u8 | Software version | high nibble.low nibble |
| 19 | u8 | RSOC | % |
| 20 | u8 | FET status | bit0 = charge, bit1 = discharge |
| 21 | u8 | Cell count | |
| 22 | u8 | NTC count (n) | |
| 23 | u16 × n | Temperatures | 0.1 K (subtract 2731 for 0.1 °C) |
| 23+2n | 9 bytes | **Extended (fw 6.6, inferred)**: `00 00 00`, full-charge capacity (u16, 10 mAh), remaining capacity (u16, 10 mAh, the same as offset 4), `00 00` | to confirm |

The extended block matched on all five batteries (for example `…2710 0C33…` = 100.00 Ah full / 31.23 Ah remaining; `…4074 3410…` = 165.00 / 133.28).

## Write and control commands
| Command | Bytes | Effect | Risk |
|---|---|---|---|
| MOSFET control | `DD 5A E1 02 00 <mode> <chk16> 77`, with mode 0 = both on, 1 = charge off, 2 = discharge off, 3 = both off | Charge and discharge switches | Medium. Cutting discharge drops the loads |

## Captured traces
Store raw captures in `docs/hardware/captures/` and link them here.
