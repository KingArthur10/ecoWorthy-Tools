# Roadmap

## Phase 0: Spec and hardware discovery
- [x] Scan for the batteries: five JBD units found
- [x] Read-only probe of each battery (basic info, cells, model). See `tools/jbd_probe.py`
- [ ] Read the protection parameters (needs factory-mode entry, which is a write. Deferred until we design for it)
- [x] Spike: BlueZ BLE from the JVM. It works (`docs/research/spike-bluez-jvm.md`)
- [ ] Optional: capture the official app's traffic for a MOSFET toggle, to confirm 0xE1 on these boards
- [ ] Confirm the control capabilities table against real hardware
- [x] Decide ADR 0001 (Kotlin Multiplatform)

## Phase 1: Read-only core
- [x] Shared JBD read driver (framing, reassembly, parsers, client with retries), tested against captured frames
- [x] Android target in `core` (project-local SDK, `tools/setup-android-sdk.sh`)
- [x] Android: favorites, nicknames, fleet overview, cached detail, search, refresh (direct mode). Builds; **not yet tested on a phone**
- [x] Polling cycle and cache (while the app is visible, refreshing readings older than 10 min)
- [x] Test on the phone over USB (2026-11-10): search, identify, add favorite, poll, detail, and refresh all work
- [ ] Settings screen (polling interval)

## Phase 2: Linux hub
- [x] Hub service: polling (10 min, with a 2 min backoff after a failure), SQLite history, JSON API, offline web dashboard with 24 h charts, CLI (`status`, `refresh`, `import-phone`, `add-favorite`, `probe`), and a systemd user service
- [x] Standalone hub dashboard: search, identify, favorites (add/rename/reorder/remove), groups (create/edit/delete/assign). The API is guarded against CSRF and DNS rebinding
- [ ] LAN access with pairing/auth (needed before the phone can use the hub, and before any control endpoints)
- [ ] Continuous passive scanning (live signal strength, faster polls)
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
