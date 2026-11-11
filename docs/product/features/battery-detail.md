# Battery detail

**Requirements:** FR-020 to FR-024
**Status:** Draft

## Summary
Everything known about one battery, drawn from its last cached reading, plus controls.

## Content
- **Header:** nickname, model, signal strength, data age, **Refresh now** button
- **Summary:** SOC, voltage, current, power, remaining Ah, and estimated time to the min or max threshold
- **Cells:** voltage of each cell, with the highest and lowest highlighted, the cell delta, and balancing status
- **Temperatures:** every sensor
- **Status:** charge and discharge MOSFET state, active protections, alarm history
- **Lifetime:** cycle count, design versus full-charge capacity, manufacture date and firmware (when the BMS reports them)
- **Controls:** see [control](control.md). Only the controls this battery supports are shown.
- **Charge profile:** the active profile (for example "Storage 60%") and a way to switch it. See
  [charge-limits-and-profiles](charge-limits-and-profiles.md).

## Behavior
- Opens immediately with cached data. Never waits on Bluetooth.
- **Refresh now:** connects, reads everything, updates the cache, and disconnects. A progress
  indicator shows while it runs. If the battery is busy or out of range, it says so and keeps
  showing the cached data.
- An optional *Live* toggle stays connected and refreshes every few seconds while the screen is
  open. It disconnects cleanly when you leave the screen.
