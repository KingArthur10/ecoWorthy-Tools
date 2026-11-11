# Battery inventory

Scanned and probed (read-only: commands 0x03, 0x04, 0x05) from the Linux PC on 2026-11-10.
Raw frames: [captures/2026-11-10-readonly-probe.json](captures/2026-11-10-readonly-probe.json).

All five batteries use the **JBD protocol**: 4S LiFePO4 (12.8 V nominal), firmware 6.6, one temperature sensor each.

| Nickname | MAC | BLE name | Capacity | Made | Cycles | SOC at probe | Notes |
|---|---|---|---|---|---|---|---|
| TBD | A5:C2:39:57:73:E3 | DP04S007L4S100A | 100 Ah | 2026-07-17 | 0 | 31% | |
| TBD | A5:C2:39:57:73:F1 | DP04S007L4S100A | 100 Ah | 2026-07-17 | 0 | 58% | **Charging at +20.0 A** during the probe |
| TBD | A5:C2:39:57:78:44 | DP04S007L4S100A | 100 Ah | 2026-07-18 | 0 | 69% | |
| TBD | A5:C2:39:57:8B:35 | DP04S007L4S100A | 100 Ah | 2026-07-17 | 0 | 32% | |
| TBD | A5:C2:37:2E:A2:52 | DP04S007L4S120A | **165 Ah** | 2024-08-26 | 9 | 81% | Older unit. The name suffix is **not** the capacity |

Total: 565 Ah nominal (about 7.2 kWh at 12.8 V).

At probe time, every battery had charge and discharge switched on and no protections active. The spread between
cells was ≤ 3 mV on every battery.

## Unidentified
| MAC | BLE name | Service | Notes |
|---|---|---|---|
| 10:5A:95:2D:2E:91 | DL105_56ZK5ENF | `0x8641` | Not a known BMS pattern. Ask the user |

## Implications
- One driver (JBD) covers every battery.
- The four 100 Ah units share a name and are distinguishable only by MAC, so nicknames are essential.
- The model string is the same as the advertised name, so the app has to read the capacity from the BMS (it can't infer it from the name).
