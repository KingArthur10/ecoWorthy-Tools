# Roadmap

Phases are proposals. Adjust them as the spec firms up.

## Phase 0: Spec and hardware discovery
- [ ] Inventory the batteries: model, capacity, BLE name, BMS type ([battery-inventory](docs/hardware/battery-inventory.md))
- [ ] Capture raw BLE traffic from each variant (nRF Connect, Android HCI snoop log)
- [ ] Finalize the requirements and the MVP scope
- [ ] Choose the tech stack (ADR 0001)

## Phase 1: MVP (read-only)
- [ ] Scan, pair, and save batteries with nicknames
- [ ] Live detail view for one battery
- [ ] Fleet overview of all batteries
- [ ] Local history logging

## Phase 2: Alerts
- [ ] Threshold alarms (SOC, voltage, cell delta, temperature, current)
- [ ] Background monitoring service with notifications

## Phase 3: Control
- [ ] Charge/discharge MOSFET toggles with confirmations
- [ ] Read and write BMS parameters (with backup and restore)

## Phase 4: Extras
- [ ] Battery groups and bank-level stats
- [ ] Widgets
- [ ] Data export
- [ ] Optional online features (TBD)
