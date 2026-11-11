# Project notes for Claude

- An Android app and a Linux hub daemon for monitoring and controlling ECO-WORTHY LiFePO4 batteries over BLE.
- Spec phase. Specs live in `docs/` and are the source of truth; update them when decisions change.
- Hard constraints: works fully offline, no accounts, no cloud. Internet is optional and only for extras.
- Targets: the user's Pixel (adb reports a Pixel 10 Pro, Android 17 / API 37) and a Linux small-form-factor PC (BlueZ).
- Each battery accepts one BLE connection at a time. Respect hub and direct modes (`docs/decisions/0003`).
- Any feature that writes to a battery must follow `docs/safety.md`.
- Requirement IDs in `docs/product/requirements.md` are stable. Never renumber them.
- Record significant decisions as ADRs in `docs/decisions/`.
- Android SDK: project-local in `.android-sdk/` (gitignored). Recreate it with `tools/setup-android-sdk.sh`. Android targets: compileSdk 37, minSdk 31.
- Build and test: `./gradlew :core:jvmTest`. Protocol code goes in `core/src/commonMain` (it's shared by Android and the Linux hub).
- Android app: `./gradlew :app:assembleDebug` (and `:app:lintDebug`, which should stay clean). The manifest deliberately has no INTERNET permission.
- Hub: `./gradlew :hub:test :hub:installDist`. Its web UI is plain HTML/JS in `hub/src/main/resources/web` and must not load anything from the internet. Its API is unauthenticated and bound to localhost. Every request must carry an allowed Host header (stops DNS rebinding), and every non-GET request needs `X-Battery-Hub: 1` (stops CSRF). Configuration edits (favorites, groups) are allowed; endpoints that change battery state are not, until pairing/auth exists.
- Favorite and group edit rules live in `core/.../domain/FleetEdits.kt` and are shared by the phone and the hub. Don't reimplement them per platform.
- Protocol tests use the real frames in `core/src/commonTest/.../Captures.kt` (generated from `docs/hardware/captures/`).
- `JbdFrame.readRequest` accepts only read commands. Write commands must be a separate, deliberate API (see `docs/safety.md`).
