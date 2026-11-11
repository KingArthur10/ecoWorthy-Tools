# Ecoworthy Battery Commander (working title)

An offline-first Android app and Linux hub for monitoring and controlling multiple ECO-WORTHY (and compatible)
LiFePO4 batteries over Bluetooth. No accounts, no cloud. It works with zero connectivity
during power outages and while camping.

**Status:** Read-only MVP. The Android app (overview, favorites, nicknames, search, detail, refresh) works on a real phone against the real batteries. The Linux BLE spike works.

## Build

```
tools/setup-android-sdk.sh  # one time: project-local Android SDK in .android-sdk/ (gitignored, about 1 GB)
./gradlew :core:jvmTest     # build the shared core and run its tests
./gradlew :core:assemble    # also builds the Android library
./gradlew :app:assembleDebug  # Android app → app/build/outputs/apk/debug/app-debug.apk
```

### Linux hub
```
tools/install-hub.sh          # build, install to ~/.local/opt, start a systemd user service
battery-hub import-phone      # copy favorites, nicknames, and groups from the phone (USB + adb)
battery-hub add-favorite A5:C2:39:57:78:44 "Garage"   # or add them by hand
battery-hub status            # all batteries, groups, and totals in the terminal
battery-hub refresh [MAC]     # read now
```
Dashboard: http://127.0.0.1:8640/ (this machine only for now; LAN access comes with pairing).
Data lives in `~/.local/share/battery-commander/` (`state.json`, the same format as the phone's, and `history.db`).

### Installing on a phone over USB
1. On the phone: Settings → About phone → tap *Build number* 7 times, then Settings → System → Developer options → turn on **USB debugging**.
2. Plug in the phone and accept the "Allow USB debugging?" prompt.
3. `.android-sdk/platform-tools/adb devices` should list it. If it says `no permissions`, run `sudo dnf install android-tools` (for its udev rules) and replug.
4. `./gradlew :app:installDebug`
On first run, Gradle downloads a JDK 21 toolchain if the system doesn't have one.
The SDK and `local.properties` (which points Gradle at it) are machine-specific and never committed.

## Code layout
| Module | Contents |
|---|---|
| `core/` | Kotlin Multiplatform shared core. `protocol/jbd`: framing, frame reassembly, parsers, and a read-only client. `transport`: the `BleLink` interface that Android and BlueZ implement |
| `app/` | Android app (Compose): favorites overview, search, detail, refresh. Read-only for now |
| `hub/` | Linux hub (JVM): `battery-hub` service and CLI. Polls favorites over BlueZ, keeps SQLite history, and serves an offline web dashboard |
| `tools/` | Dev tools (for example, the Python read-only probe) |

## Documentation map

| Area | File | Purpose |
|---|---|---|
| Vision | [docs/product/vision.md](docs/product/vision.md) | Problem, goals, non-goals, guiding principles |
| Scenarios | [docs/product/personas-and-scenarios.md](docs/product/personas-and-scenarios.md) | Outage and camping use cases |
| Requirements | [docs/product/requirements.md](docs/product/requirements.md) | Numbered functional and non-functional requirements |
| Features | [docs/product/features/](docs/product/features/) | One spec per feature area |
| Hardware | [docs/hardware/](docs/hardware/) | Battery inventory, BLE variants, protocols |
| Architecture | [docs/architecture/](docs/architecture/) | System design, data model, BLE and background service |
| UX | [docs/ux/](docs/ux/) | Screens, navigation, notifications |
| Decisions | [docs/decisions/](docs/decisions/) | Architecture Decision Records (ADRs) |
| Research | [docs/research/](docs/research/) | Existing apps, prior art |
| Testing | [docs/testing/](docs/testing/) | Test plan, hardware test matrix |
| Safety | [docs/safety.md](docs/safety.md) | Rules for commands that change battery state |
| Roadmap | [ROADMAP.md](ROADMAP.md) | Milestones and phasing |
| Open questions | [docs/open-questions.md](docs/open-questions.md) | Decisions still needed |
| Glossary | [docs/glossary.md](docs/glossary.md) | Terms and abbreviations |
