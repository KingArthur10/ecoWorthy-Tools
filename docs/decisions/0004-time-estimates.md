# 0004: Time estimates from the BMS's coulomb count, with estimate-only thresholds

**Status:** Accepted
**Date:** 2026-11-11

## Context
The user wants to know how long a battery or group will take to charge or discharge to a level they choose,
based on a rolling average. Charge profiles (ADR 0002) will later have a max SOC and a reserve, but controls
aren't built, and the first write to a battery needs a supervised test.

The phone keeps no history (only the last reading), and the hub polls every 10–60 min. Real loads cycle (a fridge
compressor), so the current at the moment of a poll can be far from the average.

## Decision
1. **Rate = Δ remaining capacity ÷ Δ time** over a 30 min rolling window (the oldest sample in the window to the
   latest reading). The JBD BMS counts coulombs continuously at 10 mAh resolution, so this is the true average
   between polls. A real capture checks it: 20.1 A by snapshot, 20.9 A by capacity change over 10 min.
2. **Fallbacks:** with no usable earlier sample, or when the flow just changed direction, use the latest current
   and mark the estimate rough. With slow polling, reach back up to 3 h for the latest earlier sample.
3. **Samples live in `StoredState.recent`** (the last 3 h, capped at 200 per battery), maintained by the shared
   `withReading()`. The phone and hub use the same code and file format; the hub doesn't query SQLite for it.
4. **Thresholds are estimate-only** (`SocThresholds` on favorites, groups, and all batteries). They never touch
   the BMS. When profiles arrive, a profile's max SOC and reserve can feed them, but they stay separate settings.
5. Estimates are computed in `summarize()` and served with the overview; the UI counts down from the reading time.

## Alternatives considered
- **Time-weighted mean of the sampled currents:** misses everything between polls; with a cycling load at a
  10 min interval, it's mostly luck.
- **Exponentially weighted moving average of power (the original spec):** same sampling problem, and the state
  would need careful handling of gaps. The window over the coulomb count is simpler and exact.
- **Reading the hub's SQLite history:** hub-only; the phone would need a second implementation.

## Consequences
- The state file grows by up to ~10 KB per battery (rewritten on each poll). Fine at this scale.
- A BMS recalibration jump in remaining capacity (e.g. snapping to full) briefly skews the rate. It's visible as a
  rough or odd estimate and clears within one window.
- Estimates to 100% are optimistic near full (charge current tapers). A taper correction is still to do.
