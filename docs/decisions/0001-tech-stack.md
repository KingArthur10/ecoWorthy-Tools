# 0001: Tech stack: Kotlin Multiplatform

**Status:** Accepted
**Date:** 2026-11-10

## Context
There are two targets: an Android app, and a Linux hub daemon on a small-form-factor PC. Both need reliable BLE and identical
protocol drivers, because both can send commands that change a battery's state.

## Decision
**Kotlin Multiplatform**: a shared Kotlin core (JBD protocol driver, domain, profiles, estimates,
sync) used by:
- **Android:** Jetpack Compose UI, Android BLE APIs, and a foreground service
- **Linux hub:** a JVM daemon using BlueZ over D-Bus, with a Ktor server for the LAN API and web UI

aiobmsble and esphome-jbd-bms serve as protocol *references*, and frames captured from the user's
batteries serve as shared test fixtures.

## Alternatives considered
- Python hub plus a Kotlin app: a faster hub, but two driver implementations that can drift apart.
- A Rust core: the most build complexity, and a weaker Android BLE story.

## Spike result (2026-11-10)
The BlueZ-from-JVM spike **succeeded**, using dbus-java 5.2.2 (see `docs/research/spike-bluez-jvm.md`). No fallback is needed.

## Consequences
- (Resolved) The Linux BLE layer was the main risk. Prototype it early (a spike: connect, read, and disconnect cleanly on
  BlueZ from the JVM). If it proves painful, fall back to a small native BlueZ helper behind the
  same interface.
