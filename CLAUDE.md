# Project notes for Claude

- An Android app and a Linux hub daemon for monitoring and controlling ECO-WORTHY LiFePO4 batteries over BLE.
- Spec phase. Specs live in `docs/` and are the source of truth; update them when decisions change.
- Hard constraints: works fully offline, no accounts, no cloud. Internet is optional and only for extras.
- Targets: a Google Pixel 11 Pro (Android) and a Linux small-form-factor PC (BlueZ).
- Each battery accepts one BLE connection at a time. Respect hub and direct modes (`docs/decisions/0003`).
- Any feature that writes to a battery must follow `docs/safety.md`.
- Requirement IDs in `docs/product/requirements.md` are stable. Never renumber them.
- Record significant decisions as ADRs in `docs/decisions/`.
