# Charge limits and profiles

**Requirements:** FR-040 to FR-045
**Status:** Draft

## Goal
Keep batteries around 60% day to day to extend their life, top them up to 100% before a trip,
and never drain below a reserve (for example, 10%).

## Implemented (2026-11-11): charge limit, enforced by the hub
- **Opt-in per battery:** the dashboard (battery menu → *Turn charge limit on…*, or the detail view) or
  `battery-hub charge-limit <battery> on|off`. The limit is the battery's own *charge to* threshold (the one the time
  estimates use). Batteries without it stay estimate-only. The web API has no authentication yet; the user agreed
  (2026-11-11) to this one control endpoint behind the existing localhost guards (Host check and CSRF header).
- **Rule** (`core/.../domain/ChargeLimit.kt`, checked after every hub reading): charge switch **off** at SOC ≥ limit;
  back **on** at limit − 5%. Raising the limit above the current SOC, or turning the limit off, resumes charging on
  the next pass (within ~30 s). Only the charge switch is touched; loads keep power.
- **Never fights the BMS:** nothing is switched while a real protection is active, and a charge switch is only turned
  back on if the BMS's software-lock flag shows it was switched off over Bluetooth.
- **Polling:** a limited battery charging within 5% of its limit is read every minute (overshoot ≈ 0.3% at 20 A into
  100 Ah). A failed connection backs off 2 minutes, so a weak link can overshoot more (tested: 3–4%).
  In *Manual only* refresh mode, limits are only checked when you refresh.
- **Logged** in the command log with source `profile` (`battery-hub log`).
- **The dashboard** shows "Held at 70% limit" instead of a fault, and the detail view explains the limit.
- **Not built yet:** profiles (Storage/Trip/Outage), the min-SOC reserve, enforcement by the phone, and series strings
  (members of a series group are refused).
- **Live test (2026-11-11, Test 3, 20 A charger, 5 min interval):** limit 70% turned on at 71%. The first switch-off
  (73%) lost its connection; the retry stopped it at 74%. Raising the limit to 77% resumed charging (after one more
  failed connect), then 1-minute polls stopped it at 77.0%. Back to 70%: held, no further switching.
- **If the hub stops while holding a battery, it stays held** (the software lock persists in the BMS); see ADR 0002.

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
