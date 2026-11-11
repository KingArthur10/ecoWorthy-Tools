# Open questions

| # | Question | Status |
|---|---|---|
| 1 | Which battery models and BLE variants do you own? (battery inventory) | Open |
| 2 | ~~What does "full command and control" mean?~~ Answered 2026-11-10: charge toggle, max-SOC limit, min-SOC reserve, charge rate. See the control spec | Answered |
| 3 | Should alarms take actions automatically? Current proposal: profile limits act automatically; cutting discharge at the reserve is opt-in | Proposed |
| 4 | Charge-rate control isn't possible through a standard BMS. Which chargers, solar controllers, or DC-DC converters do you use? Are any Bluetooth-controllable? | Open |
| 5 | Tech stack (ADR 0001): Kotlin Multiplatform is recommended | Open |
| 6 | At home, should the phone always go through the hub (ADR 0003)? | Proposed |
| 7 | Default polling interval: 10 min proposed, adaptive near limits | Proposed |
| 8 | During an outage, does the LAN stay up (is the router on battery)? If not, the phone falls back to direct mode | Open |
| 9 | Distribution: sideloaded APK, F-Droid, or Play Store? Open source? | Open |
| 10 | Which internet-only extras, if any (weather or solar forecasts)? | Open |
