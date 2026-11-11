# 0001: Tech stack

**Status:** Proposed
**Date:** 2026-11-10

## Context
Android-only for now, with heavy BLE and background work, so the app needs reliable
foreground services.

## Options
- Native Kotlin + Jetpack Compose (best BLE and background-service control)
- Flutter (cross-platform, but BLE goes through a plugin layer)
- A PWA using Web Bluetooth (no background operation, which rules it out for alarms)

## Decision
TBD. Leaning toward native Kotlin + Compose.
