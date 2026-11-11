# Open questions

| # | Question | Status |
|---|---|---|
| 1 | ~~Which batteries?~~ Five JBD batteries found (4× `DP04S007L4S100A`, 1× `…120A`). See the inventory | Answered 2026-11-10 |
| 1a | What is `DL105_56ZK5ENF` (10:5A:95:2D:2E:91)? Is it battery-related? | Open |
| 1b | ~~Capacity~~ (4× 100 Ah and 1× 165 Ah). Which physical battery is which MAC? (Match them using the SOC table in the inventory) | Open |
| 1c | Battery A5:C2:39:57:73:F1 was charging at 20 A during the probe. What charger was it on? (Useful for testing the charge limit) | Open |
| 2 | ~~What does "full command and control" mean?~~ Charge toggle, max-SOC limit, min-SOC reserve | Answered 2026-11-10 |
| 3 | Should alarms take actions automatically? Proposal: profile limits act automatically; cutting discharge at the reserve is opt-in | Proposed |
| 4 | ~~Charge-rate control~~. Generic chargers, so it's out of scope | Answered 2026-11-10 |
| 5 | ~~Tech stack~~. Kotlin Multiplatform (ADR 0001) | Answered 2026-11-10 |
| 6 | Hub mode at home, and when camping with the PC (ADR 0003). Which local link do you prefer: a phone hotspot or a hub access point? | Open |
| 7 | Default polling interval: 10 min proposed, adaptive near limits | Proposed |
| 8 | What powers the Linux PC when camping (one of these batteries, through an inverter or DC)? It affects the power budget | Open |
| 9 | Distribution: sideloaded APK, F-Droid, or Play Store? Open source? | Open |
| 10 | Which internet-only extras, if any? | Open |
