# Battery inventory

Found by a BLE scan from the Linux PC (strixalot-fed, BlueZ) on 2026-11-10. All five ECO-WORTHY units
advertise service `0xFF00`, which is the **JBD / Xiaoxiang protocol**.

Reading the name: `DP04S007` = JBD board family, `L4S` = 4 cells in series (12.8 V LiFePO4),
`100A`/`120A` = the BMS current rating (probably *not* the Ah capacity; to be confirmed with a read).

| Nickname | MAC | BLE name | BMS variant | Capacity (Ah) | Notes |
|---|---|---|---|---|---|
| TBD | A5:C2:39:57:73:E3 | DP04S007L4S100A | JBD | TBD | |
| TBD | A5:C2:39:57:73:F1 | DP04S007L4S100A | JBD | TBD | |
| TBD | A5:C2:39:57:78:44 | DP04S007L4S100A | JBD | TBD | |
| TBD | A5:C2:39:57:8B:35 | DP04S007L4S100A | JBD | TBD | |
| TBD | A5:C2:37:2E:A2:52 | DP04S007L4S120A | JBD | TBD | Different BMS rating, possibly a larger battery |

## Unidentified
| MAC | BLE name | Service | Notes |
|---|---|---|---|
| 10:5A:95:2D:2E:91 | DL105_56ZK5ENF | `0x8641` | Not a known BMS pattern. Ask the user whether it's battery-related |

## Implications
- Every battery uses the same, best-documented protocol, so **JBD is the only driver needed for v1**.
- JBD MOSFET control (command 0xE1) is documented, so charge limits enforced by the app are feasible.
- The MACs share the prefix `A5:C2:…`, so the app can't tell batteries apart by name. Nicknames are essential, and
  the *Identify* action (a one-time read of SOC and voltage) is how you match a MAC to a physical battery.
