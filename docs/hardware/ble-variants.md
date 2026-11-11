# ECO-WORTHY BLE variants

ECO-WORTHY has shipped several Bluetooth implementations. Identify them by the advertised name.

| Advertised name | Hardware | Protocol doc | Support plan |
|---|---|---|---|
| `DP04S…`, `JBD-…` | JBD / Xiaoxiang BMS | [protocols/jbd.md](protocols/jbd.md) | Yes |
| `ECO-WORTHY…` | BW02 BT/WiFi module | [protocols/ecoworthy-bw02.md](protocols/ecoworthy-bw02.md) | Yes |
| `T-12…`, `T-24…` | Redodo-type BMS | [protocols/redodo.md](protocols/redodo.md) | Yes |
| `ECO…` (older) | Classic Bluetooth (not BLE) | [protocols/classic-bt.md](protocols/classic-bt.md) | Investigate |

Source: the supported-devices list in [BMS_BLE-HA](https://github.com/patman15/BMS_BLE-HA).
Verify each against real hardware.

## Known constraints
- **One connection at a time.** Each battery accepts a single BLE client, so the app and
  the official app (or another phone) can't both be connected.
- **BW02 lockup.** After an unclean disconnect, a BW02 module may stay silent until the battery
  is power-cycled (reported in kbennett2000/linux-lifepo4-bms-monitor).
- **Calculated capacity.** On some variants, remaining Ah is computed from SOC, not measured.
