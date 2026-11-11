# Ecoworthy Battery Commander (working title)

An offline-first Android app and Linux hub for monitoring and controlling multiple ECO-WORTHY (and compatible)
LiFePO4 batteries over Bluetooth. No accounts, no cloud. It works with zero connectivity
during power outages and while camping.

**Status:** Spec mostly done. The shared Kotlin core has a read-only JBD protocol driver, tested against real captures.

## Build

```
./gradlew :core:jvmTest     # build the shared core and run its tests
```
On first run, Gradle downloads a JDK 21 toolchain if the system doesn't have one.

## Code layout
| Module | Contents |
|---|---|
| `core/` | Kotlin Multiplatform shared core. `protocol/jbd`: framing, frame reassembly, parsers, and a read-only client. `transport`: the `BleLink` interface that Android and BlueZ implement |
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
