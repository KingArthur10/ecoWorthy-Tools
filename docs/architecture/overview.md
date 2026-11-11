# Architecture overview

**Status:** Draft

## Two deployments, one system
1. **Linux hub**, a daemon on a small-form-factor PC. Polls the favorites on a schedule, keeps the
   history, enforces charge profiles, and sends alarms. It serves a local API and web UI on the LAN.
2. **Android app**, the main interface. It works in two modes:
   - **Hub mode:** the hub is reachable on the LAN, so the phone reads its data and sends commands
     *through the hub*. The phone doesn't poll the batteries over Bluetooth itself.
   - **Direct mode:** no hub (for example, camping). The phone does its own Bluetooth polling, with
     its own cache, profile enforcement, and alarms.

Switching between modes is automatic and visible on screen. The hub never needs the internet, only a local
IP link: home Wi-Fi, a phone hotspot (which works with no cellular service), or the hub's own Wi-Fi access point (see ADR 0003).

**Implemented (2026-11-10):** `hub/`: `battery-hub serve` (poller + Ktor on 127.0.0.1:8640), SQLite history,
a JSON API (`/api/overview`, `/api/batteries/{mac}/history`, `POST …/refresh`), and the static dashboard. The hub shares
`StoredState` and `summarize()` with the phone, through `core`.

The Linux PC is also a **command station** in its own right: its web UI and CLI offer full monitoring and
control (FR-082, FR-085).

## Why hub mode matters
Each battery accepts **one Bluetooth connection at a time**. If the hub and the phone both
poll, they'll collide. In hub mode, the hub is the only Bluetooth client.
Even in direct mode, the poll-and-disconnect cycle keeps connections short, so collisions are
rare and get retried.

## Who enforces charge profiles
Exactly one node at a time owns enforcement for each battery:
- The hub, when it's running and sees the battery
- Otherwise, the phone in direct mode
- Ownership is recorded on each battery and shown in the UI. When the phone can reach the hub, the phone defers to it.

## Layers (on both platforms)
1. UI
2. Domain: favorites, banks, readings, profiles, alarms, estimates
3. Protocol drivers: one per BMS variant, behind a common interface split into read and write capabilities
4. Bluetooth transport: scanning, connecting, retries, clean disconnects
5. Storage: a local database for favorites, the cache, history, and the command log
6. Scheduler: polling cycles and background or foreground service

## Sync between the hub and the phone
- Settings (favorites, nicknames, banks, profiles, alarm rules) sync both ways. The newer change wins,
  judged per record.
- When the phone reconnects to the hub, it downloads the hub's history. The phone also uploads any direct-mode history it recorded.
- Pairing happens on the LAN, with a one-time code. No accounts.

See ADR 0001 (tech stack) and ADR 0003 (hub and direct modes).
