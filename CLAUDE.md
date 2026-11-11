# Project notes for Claude

Battery Commander: an offline-first **Android app** and **Linux hub** for monitoring (and, later, controlling)
the user's ECO-WORTHY LiFePO4 batteries over BLE. Specs live in `docs/` and are the source of truth; update them
when decisions change. Start with `README.md` (doc map, build and install), `ROADMAP.md` (what's done and next), and
`docs/open-questions.md`.

## Status (last session: 2026-11-10)
- **Done and tested on real hardware:** the read-only JBD driver (`core`); the Android app (favorites, nicknames,
  search/identify, overview with all-batteries totals and groups, detail, refresh); the Linux hub (`battery-hub`
  service, SQLite history, standalone web dashboard with search/favorites/groups/charts, CLI).
- **Installed on the user's PC:** the hub runs as a systemd *user* service (`battery-hub.service`) on
  http://127.0.0.1:8640/, with data in `~/.local/share/battery-commander/`. Its favorites are currently placeholders
  ("Test 1"–"Test 5"); the user hasn't given them real names yet. The phone has its own favorites (some named
  "Close Battery 78:44", "second closest", "test", "another test", "big boy").
- **Next milestone (the user hasn't chosen yet):**
  1. Pairing and LAN access: phone ↔ hub, hub mode vs direct mode (ADR 0003), and preventing both from connecting to a battery at once.
  2. Controls: charge/discharge MOSFETs, Storage/Trip profiles, reserve (`docs/product/features/control.md`,
     `charge-limits-and-profiles.md`, ADR 0002). **The first write to a real battery must be a single supervised test,
     with the user's explicit OK** (toggle charge off/on on one idle battery). Follow `docs/safety.md`.
- **Smaller gaps:** no Android background monitoring (it only polls while visible; needs a foreground service); no
  Android settings screen (the 10 min interval is fixed); no time-to-empty/full estimates; no nested banks (2S2P);
  the hub has no continuous passive scanning.

## Hardware facts (verified; details in docs/hardware/)
- 5 batteries, all **JBD BMS**, firmware 6.6, 4S: four 100 Ah (`DP04S007L4S100A`, MACs A5:C2:39:57:73:E3 / 73:F1 /
  78:44 / 8B:35) and one 165 Ah (`DP04S007L4S120A`, A5:C2:37:2E:A2:52). The name suffix is *not* the capacity.
- GATT: service FF00, notify FF01, write FF02 (write without response). A Telink OTA service is also present: **never write to it**.
- **One BLE connection per battery.** The BMS **ignores the first request after each connect** (on both BlueZ and Android);
  the 1 s response timeout plus retry in `JbdClient` handles it.
- **BlueZ can complete a "failed" Connect() in the background** and hold the battery. Every failure path must
  disconnect and confirm (`BluezBleLink.kt`). Range from the PC is weak (−80 to −100 dBm for some batteries), so a
  failed poll is routine: retry and mark the data stale.
- An Android refresh takes about 2.3 s; a hub poll about 5–10 s (most of it discovery).

## Rules
- Hard constraints: fully offline, no accounts, no cloud. The Android manifest deliberately has no INTERNET permission.
  The hub web UI loads nothing from the internet.
- Any feature that writes to a battery must follow `docs/safety.md`. `JbdFrame.readRequest` accepts only read
  commands; writes must be a separate, deliberate API.
- Hub API: unauthenticated and bound to localhost. Every request must carry an allowed Host header (stops DNS
  rebinding), and every non-GET needs `X-Battery-Hub: 1` (stops CSRF). Config edits are allowed; **battery-state-changing
  endpoints are not allowed until pairing/auth exists** (local-only control behind the same guards would need the user's agreement).
- Shared logic belongs in `core/src/commonMain`: protocol, `domain/` (BatteryReading, aggregate(), StoredState +
  summarize(), FleetEdits rules). Don't reimplement it per platform.
- Requirement IDs in `docs/product/requirements.md` are stable. Never renumber them. Record decisions as ADRs in `docs/decisions/`.

## Build, run, test
- The system Java is a JRE 25 with no javac. Gradle downloads a JDK 21 toolchain automatically (foojay).
- `./gradlew :core:jvmTest :hub:test` (unit tests, about 67), `./gradlew :app:assembleDebug :app:lintDebug` (lint must stay clean).
- Android SDK: project-local `.android-sdk/` (gitignored, along with `local.properties`). Recreate it with `tools/setup-android-sdk.sh`.
  compileSdk 37, minSdk 31. adb: `.android-sdk/platform-tools/adb`. Install: `./gradlew :app:installDebug` (phone over USB).
- Hub: `tools/install-hub.sh` (stops the service, reinstalls, restarts). Manual: `./gradlew :hub:installDist`, then
  `hub/build/install/battery-hub/bin/battery-hub serve|status|scan|refresh|add-favorite|import-phone|probe`.
  For experiments, run a second instance with `XDG_DATA_HOME=<scratch dir>` and `--port 8641` so the user's data isn't
  touched; stop the user's service first if you need the radio exclusively.
- Dashboard screenshots: `firefox --headless --screenshot` fires before fetches finish, so load a harness copy of
  `index.html` with a stubbed `window.fetch` returning captured API JSON (that's how it was checked last time).
- Protocol test fixtures: `core/src/commonTest/.../Captures.kt` (real frames, from `docs/hardware/captures/`).
- Always check that no battery is left connected after BLE experiments: `bluetoothctl devices Connected`.
