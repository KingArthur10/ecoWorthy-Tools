# 0001: Tech stack

**Status:** Proposed
**Date:** 2026-11-10

## Context
There are two targets: an Android app, and a Linux hub daemon on a small-form-factor PC. Both need reliable BLE
and the same protocol drivers. Getting the protocol right is the riskiest part, so the two
targets shouldn't drift apart.

## Options
A. **Kotlin Multiplatform**: a shared Kotlin core (protocol drivers, domain, estimates) used by
   the Android app (Compose) and a JVM daemon on Linux. Linux BLE goes through BlueZ D-Bus
   (for example, the `blessed-bluez` library or a thin D-Bus binding). One language and one set of drivers.
B. **Python hub plus a native Kotlin app**: the hub uses `bleak` and `aiobmsble`, which already decodes every
   ECO-WORTHY BLE variant. The Android app has its own Kotlin drivers. That's the fastest hub, but
   two driver implementations. Keep them in step with shared test fixtures (captured frames).
C. **Rust core** (btleplug) bound into Android through UniFFI and used directly on Linux. Strong shared core,
   but the most build complexity and the weakest Android BLE story.

## Recommendation
**A (Kotlin Multiplatform)**, using aiobmsble and esphome-jbd-bms as *references* (and their
captured frames as test fixtures). A single codebase for the control logic matters most,
because both nodes can send commands that change state.
Fallback if Linux BLE from the JVM proves painful: B.

## Decision
TBD (needs the user's input)
