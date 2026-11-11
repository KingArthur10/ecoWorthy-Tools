# Roadmap

## Phase 0: Spec and hardware discovery
- [ ] Fill in the battery inventory (models and BLE names)
- [ ] Capture BLE traffic from the official app for each variant: reads, MOSFET toggles, and settings (HCI snoop log)
- [ ] Confirm the control capabilities table against real hardware
- [ ] Decide ADR 0001 (tech stack)

## Phase 1: Read-only core
- [ ] Shared protocol drivers (reads), tested against captured frames
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
