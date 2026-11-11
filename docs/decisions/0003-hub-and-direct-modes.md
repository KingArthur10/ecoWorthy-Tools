# 0003: Hub mode and direct mode

**Status:** Proposed
**Date:** 2026-11-10

## Context
The batteries accept one BLE connection at a time. The user wants a Linux small-form-factor PC polling at home, and the phone usable at home and away.

## Decision
When the hub is reachable on the LAN, the phone talks only to the hub. Otherwise the phone talks
directly to the batteries over Bluetooth. Exactly one node owns polling and profile enforcement for each battery.

## Consequences
- The hub needs a LAN API with authentication (a one-time pairing code, with no accounts).
- The phone needs sync logic and a clear indicator showing which mode it's in.
- At home, the hub's Bluetooth range has to reach every battery (an extra adapter or a USB extension may be needed).
