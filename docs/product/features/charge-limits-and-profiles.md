# Charge limits and profiles

**Requirements:** FR-040 to FR-045
**Status:** Draft

## Goal
Keep batteries around 60% day to day to extend their life, top them up to 100% before a trip,
and never drain below a reserve (for example, 10%).

## Profiles
A profile is a named set of {max SOC, resume SOC, min SOC (reserve), reserve action}.

| Profile | Max | Resume charging at | Reserve | Reserve action |
|---|---|---|---|---|
| Storage (default) | 60% | 55% | 10% | Notify |
| Trip / Full | 100% | n/a | 10% | Notify |
| Outage | 100% | n/a | 10% | Notify, then cut discharge (opt-in) |

Profiles can be applied to a single battery or to a whole bank. A temporary profile can revert on a schedule: for example,
"Trip until Sunday 6 pm, then Storage".

## How the limit is enforced (proposed: by the app; see ADR 0002)
The battery has no "stop at 60%" setting, so the app enforces the limit with the charge MOSFET:
1. A polling cycle reads SOC ≥ max. The app turns the **charge MOSFET off** and logs it.
2. SOC falls to ≤ resume. The app turns the **charge MOSFET back on**. This gap (hysteresis) stops the
   app from flipping the switch back and forth.
3. Switching to the Trip profile turns charging back on immediately.

**Fails safe:** if the app or hub stops running, the battery simply carries on with its own BMS
protection (it charges to 100%). That's safe for the battery, just less ideal for its lifespan.

### Why not set it inside the BMS?
You could lower the BMS's overvoltage protection to stop charging early, but:
- LiFePO4 voltage is almost flat between about 20% and 90%, so a voltage cutoff can't land reliably at 60%.
- It misuses a safety protection (it would trip protection alarms, and an error could disable real protection).
- It's rejected as the default. It could return as an expert-only option if testing shows it's
  reliable on your hardware.

### Polling interval versus overshoot
The SOC can rise past the limit between polls. At a 10-minute interval with a 20 A charge into
100 Ah, the overshoot is up to about 3%. While a limit is active and the battery is charging,
the poller shortens the interval as SOC approaches max (see polling).

## Reserve (min SOC)
- **Notify** (default): a notification at reserve + 5% and another at reserve.
- **Cut discharge** (opt-in): turns the discharge MOSFET off at the reserve. This cuts power to the loads,
  so it gives a warning first (default: 2 minutes, with a cancel option on the notification). Off by default,
  because it could cut a fridge or medical device during an outage.

## SOC accuracy caveat
Most BMSs estimate SOC by counting current in and out, and that estimate drifts over time. Holding at 60% for months stops the BMS
from recalibrating at full. The app suggests a full charge every N weeks (configurable), and
the Trip profile does it naturally.
