# Roadmap

## Phase 0: Spec and hardware discovery
- [x] Scan for the batteries: five JBD units found
- [x] Read-only probe of each battery (basic info, cells, model). See `tools/jbd_probe.py`
- [ ] Read the protection parameters (needs factory-mode entry, which is a write. Deferred until we design for it)
- [ ] Spike: BlueZ BLE from the JVM (connect, read, and disconnect cleanly)
- [ ] Optional: capture the official app's traffic for a MOSFET toggle, to confirm 0xE1 on these boards
- [ ] Confirm the control capabilities table against real hardware
- [x] Decide ADR 0001 (Kotlin Multiplatform)

## Phase 1: Read-only core
- [x] Shared JBD read driver (framing, reassembly, parsers, client with retries), tested against captured frames
- [ ] Android target in `core` (needs the Android SDK)
- [ ] Android: favorites, nicknames, fleet overview, cached detail, search, refresh (direct mode)
- [ ] Polling cycle and cache

## Phase 2: Linux hub
- [ ] Hub daemon: polling, history, LAN API, basic web UI
- [ ] Android hub mode, pairing, and sync

## Phase 3: Control and profiles
- [ ] Charge and discharge MOSFET control, including the series-bank rule
- [ ] Charge profiles enforced by the app, with adaptive polling
- [ ] Reserve notifications, and opt-in discharge cut

## Phase 4: Banks, alarms, and history
- [ ] Bank editor, diagram, and combined stats
- [ ] Runtime and charge-time estimates
- [ ] Alarm rules and notifications, history charts, CSV export

## Phase 5: Extras
- [ ] Widgets
- [ ] BMS parameter editing (expert mode)
- [ ] Charger integration for charge-rate control
