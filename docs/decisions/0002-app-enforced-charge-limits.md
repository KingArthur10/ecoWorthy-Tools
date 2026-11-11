# 0002: Enforce charge limits in the app, using the charge MOSFET

**Status:** Proposed
**Date:** 2026-11-10

## Context
The user wants batteries held at about 60% for long-term storage, and charged to 100% before trips.
Drop-in BMSs have no SOC-based charge-stop setting.

## Decision
The app (hub or phone) turns the charge MOSFET off at max SOC and back on at the resume SOC, with hysteresis.

## Alternatives considered
- Lowering the BMS's overvoltage protection: rejected as the default. It's imprecise on the flat
  LiFePO4 curve, and it misuses a safety protection.
- Controlling the charger: depends on the charger, so it's future scope.

## Consequences
- If the controller isn't running, the battery charges to 100% (fails safe).
- SOC may overshoot between polls. Mitigated by the adaptive polling interval.
- Every battery needs a working MOSFET-control driver. For BW02, that needs a protocol capture first.
