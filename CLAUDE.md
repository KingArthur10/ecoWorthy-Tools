# Project notes for Claude

- This is an Android app for monitoring and controlling ECO-WORTHY LiFePO4 batteries over BLE.
- It is in the spec phase. Specs live in `docs/`; keep them the source of truth and update them when decisions change.
- Hard constraints: works fully offline, no accounts, no cloud dependency. Internet is optional and only for extras.
- Target device: Google Pixel 11 Pro (Android). See `docs/product/requirements.md` for minimum SDK.
- Any feature that writes to a battery (MOSFET toggles, BMS parameters) must follow `docs/safety.md`.
- Record significant decisions as ADRs in `docs/decisions/` using `0000-template.md`.
