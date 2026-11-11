# Vision

## Problem
The official ECO-WORTHY app falls short for someone who owns several batteries:
- Batteries can't be given nicknames
- There's no overview of all batteries at once
- There are no configurable alarms (for example, on charge thresholds)
- Command and control is limited

Third-party apps (Overkill Solar, Cellmate, PackDial, etc.) each cover part of this, but none
covers all of it. See [existing-apps](../research/existing-apps.md).

## Goals
1. Full command and control of every battery the BMS allows, with safety guardrails.
2. A fleet view: every battery's state on one screen.
3. A user-defined nickname for each battery.
4. Configurable alarms that work while the phone is asleep.
5. Fully functional offline, with no account.
6. Charge profiles: hold at about 60% for storage, charge to 100% before trips, and keep a minimum reserve.
7. Banks: model series and parallel wiring, and estimate runtime or time to charge.
8. Two platforms: a Linux hub (a small-form-factor PC polling around the clock) and an Android app (at home or on the go).

## Non-goals (for now)
- iOS support
- Native desktop GUI (the hub's web UI covers it)
- A cloud sync or account system
- Managing chargers or inverters (could be revisited later; see open questions)

## Guiding principles
- **Offline first.** Every core feature works with no network at all.
- **Local data.** Data stays on the phone unless the user exports it.
- **Safe by default.** Commands that change state need confirmation and can be undone where possible.
- **Battery-friendly.** Polling and background work are tuned so the phone lasts through an outage.
- **Clean BLE.** Disconnect cleanly every time. Some modules lock up after an unclean disconnect.
