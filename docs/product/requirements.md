# Requirements

IDs are stable from here on (baseline 2026-11-10). Never renumber; deprecate instead.
Priority: **M** = must (MVP), **S** = should, **C** = could.

## Functional

### Favorites and nicknames ([spec](features/device-management.md))
| ID | Requirement | Pri |
|---|---|---|
| FR-001 | Scan for and identify supported batteries over BLE | M |
| FR-002 | Add a discovered battery to favorites by long-pressing it in the Discovered list | M |
| FR-003 | Each favorite has a user nickname, plus optional capacity, color, and notes | M |
| FR-004 | Detect the BMS protocol variant automatically, with a manual override | M |
| FR-005 | Rename, reorder, and remove favorites | M |
| FR-006 | An *Identify* action does a one-time read of a battery that isn't a favorite yet | S |

### Fleet overview ([spec](features/fleet-overview.md))
| ID | Requirement | Pri |
|---|---|---|
| FR-010 | On launch, show each favorite's last cached data right away | M |
| FR-011 | Each card shows SOC, voltage, current, temperature, state, data age, and signal strength | M |
| FR-012 | The list scrolls when favorites outnumber the screen | M |
| FR-013 | A Search button lists batteries that aren't favorites, below the favorites | M |
| FR-014 | A "Refresh all" action starts a polling cycle right away | M |
| FR-015 | A bank summary row with combined SOC and time estimate (SOC, Ah, and Wh done; time estimate still to do) | S |
| FR-017 | Each favorite card shows remaining and total capacity (Ah and Wh) | M |
| FR-018 | An "All batteries" total card: combined SOC, Ah, Wh, and power, with partial data flagged | M |
| FR-016 | Show the signal strength's source (phone or hub) | S |

### Battery detail ([spec](features/battery-detail.md))
| ID | Requirement | Pri |
|---|---|---|
| FR-020 | Opens with the cached data for one battery: cells, temperatures, MOSFETs, protections, cycles | M |
| FR-021 | A "Refresh now" button does an immediate full read | M |
| FR-022 | Estimated time to the min or max threshold | S |
| FR-023 | A Live mode stays connected while the screen is open | C |
| FR-024 | Show the active charge profile and allow switching it | S |

### Command and control ([spec](features/control.md))
| ID | Requirement | Pri |
|---|---|---|
| FR-030 | Show only the controls the battery's BMS supports, and explain the missing ones | M |
| FR-031 | Toggle the charge MOSFET | M |
| FR-032 | Toggle the discharge MOSFET (with a strong confirmation) | S |
| FR-033 | Apply MOSFET actions to every member of a series bank together | M |
| FR-034 | Read BMS parameters | S |
| FR-035 | Write BMS parameters (expert mode, backup first) | C |
| FR-036 | ~~Control the charge current through a Bluetooth-controllable charger~~. **Deprecated 2026-11-10:** the user's chargers are generic | n/a |

### Charge limits and profiles ([spec](features/charge-limits-and-profiles.md))
| ID | Requirement | Pri |
|---|---|---|
| FR-040 | A max-SOC limit enforced by the app, using the charge MOSFET with a resume threshold | M |
| FR-041 | A min-SOC reserve, which notifies by default | M |
| FR-042 | Optional: cut discharge at the reserve, with a cancellable warning | S |
| FR-043 | Named profiles (Storage, Trip, Outage) for each battery or bank | M |
| FR-044 | Temporary profiles that revert on a schedule | S |
| FR-045 | A reminder to do a periodic full charge for SOC calibration | C |

### Banks and estimates ([spec](features/bank-topology-and-estimates.md))
| ID | Requirement | Pri |
|---|---|---|
| FR-050 | Define banks by hand as series and parallel groups (flat groups done; nesting such as 2S2P still to do) | S |
| FR-051 | A visual diagram of each bank | S |
| FR-052 | Combined voltage, capacity, SOC, and energy for each bank | S |
| FR-053 | Time-to-reserve and time-to-max estimates from the average power | S |
| FR-054 | Warn about mismatched batteries within a bank | C |
| FR-055 | Show how confident each estimate is | C |

### Alarms ([spec](features/alarms-and-notifications.md))
| ID | Requirement | Pri |
|---|---|---|
| FR-060 | User thresholds for each battery or bank: SOC, voltage, cell delta, temperature, current | M |
| FR-061 | Notify while the phone is asleep (in direct mode) or from the hub (in hub mode) | M |
| FR-062 | Alert on a stale or unreachable battery, and on BMS protection events | S |
| FR-063 | Hysteresis and repeat limits, to avoid a flood of notifications | M |
| FR-064 | Alarm actions (for example, cut discharge) are opt-in for each rule | S |

### History ([spec](features/history-and-logging.md))
| ID | Requirement | Pri |
|---|---|---|
| FR-070 | Log every polled reading locally | M |
| FR-071 | History charts for each battery or bank | S |
| FR-072 | Export to CSV | C |
| FR-073 | A command log of every state-changing action | M |

### Platforms ([architecture](../architecture/overview.md))
| ID | Requirement | Pri |
|---|---|---|
| FR-080 | An Android app (works in hub mode and direct mode) | M |
| FR-081 | A Linux hub daemon that polls, enforces profiles, and keeps history | M |
| FR-082 | The hub serves a LAN API for the phone, plus a web UI with **full monitoring and control** (the Linux PC is a command station at home). Monitoring, search, favorites, and groups are done (standalone, no phone needed); control is still to do | M |
| FR-083 | Switch automatically between hub mode and direct mode | S |
| FR-084 | Settings sync between the phone and the hub | S |
| FR-085 | A hub CLI for status and commands (scriptable). Status, refresh, import, and add are done | S |
| FR-086 | The phone can reach the hub with **no internet**, through a local link (a phone hotspot or a hub access point) | S |

## Non-functional
| ID | Requirement |
|---|---|
| NFR-001 | Every core feature works with no internet. The hub needs the LAN only |
| NFR-002 | No account, login, or cloud dependency. Pairing with the hub uses a local one-time code |
| NFR-003 | Android: runs on the Pixel 11 Pro. minSdk 31 (Android 12, where the BLUETOOTH_SCAN/CONNECT permissions start), compileSdk 37. Linux: runs on any distribution with BlueZ (Fedora, Ubuntu, Raspberry Pi OS) |
| NFR-004 | Android background monitoring keeps phone battery drain low (target TBD) |
| NFR-005 | Every BLE session ends in a clean disconnect |
| NFR-006 | No analytics or telemetry |
| NFR-007 | Readable in direct sunlight and in the dark |
| NFR-008 | Only one node at a time controls a given battery (no conflicting commands) |
