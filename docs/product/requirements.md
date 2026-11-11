# Requirements

IDs are stable. Never renumber; deprecate instead.
Priority: **M** = must (MVP), **S** = should, **C** = could.

## Functional

### Discovery and management
| ID | Requirement | Priority |
|---|---|---|
| FR-001 | Scan for and identify supported batteries over BLE | M |
| FR-002 | Save a battery with a user nickname, capacity, and optional notes or icon | M |
| FR-003 | Detect the BMS protocol variant automatically | M |
| FR-004 | Rename, reorder, and remove saved batteries | M |

### Monitoring
| ID | Requirement | Priority |
|---|---|---|
| FR-010 | Fleet overview showing every saved battery's SOC, voltage, current, and status | M |
| FR-011 | Battery detail: cell voltages, temperatures, cycles, MOSFET state, protection flags | M |
| FR-012 | Estimated time to full or empty | S |
| FR-013 | Battery groups with combined stats | C |

### Alarms
| ID | Requirement | Priority |
|---|---|---|
| FR-020 | User-defined thresholds for each battery (SOC, voltage, cell delta, temperature, current) | M |
| FR-021 | Notify when a threshold is crossed, including while the phone is asleep | M |
| FR-022 | Alert on connection loss or BMS protection events | S |

### Control
| ID | Requirement | Priority |
|---|---|---|
| FR-030 | Toggle the charge and discharge MOSFETs | S |
| FR-031 | Read BMS parameters | S |
| FR-032 | Write BMS parameters, with backup first and validation | C |
| FR-033 | TBD: the full list of controls the user wants (see open questions) | ? |

### History
| ID | Requirement | Priority |
|---|---|---|
| FR-040 | Log readings locally | S |
| FR-041 | History charts | S |
| FR-042 | Export to CSV | C |

## Non-functional
| ID | Requirement |
|---|---|
| NFR-001 | Every core feature works with no network connection |
| NFR-002 | No account, login, or cloud dependency |
| NFR-003 | Runs on the Pixel 11 Pro. Minimum Android version TBD |
| NFR-004 | The background service uses a small, measured share of the phone's battery (target TBD) |
| NFR-005 | Every BLE session ends in a clean disconnect |
| NFR-006 | No analytics or telemetry |
| NFR-007 | Readable in direct sunlight and in the dark (high-contrast and dark themes) |
