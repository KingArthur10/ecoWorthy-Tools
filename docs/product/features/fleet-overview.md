# Fleet overview (home screen)

**Requirements:** FR-010 to FR-016
**Status:** Draft. Captured from the user's ideas, 2026-11-10.

## Summary
The first screen you see. It shows each **favorite** battery's last cached data, so it's
useful right away, even before any battery has connected. A **Search** button finds
batteries that aren't favorites yet and lists them below the favorites.

## Layout
```
┌──────────────────────────────────────┐
│ Batteries        [⟳ all] [🔍 Search] │
│ Bank: House (2P) 61% · 4h12m to 10%  │  ← bank summary (when banks exist)
├──────────────────────────────────────┤
│ ★ Garage A                ▂▄▆_  -71  │  ← nickname, signal bars, RSSI dBm
│   61%  13.21V  -4.2A  22°C           │
│   ▮▮▮▮▮▮▱▱▱▱  Discharging · 2m ago   │  ← how old the data is (always visible)
├──────────────────────────────────────┤
│ ★ Camp 1                  ▂___  -92  │
│   100% 13.45V   0.0A  18°C           │
│   ⚠ Charge off (limit)  · 14m ago    │
├──────────────────────────────────────┤
│   … scrolls when there are more …    │
├──────── Discovered (not favorites) ──┤
│   ECO-WORTHY 02_4F1A      ▂▄__  -80  │  ← long-press → "Add to favorites"
│   DP04S007L4S100A         ▂▄▆█  -58  │
└──────────────────────────────────────┘
```

## Behavior
- **On launch:** show the cached data for every favorite at once. Never block on Bluetooth.
- **Favorites:** shown in a user-defined order. Each card shows the nickname, SOC, voltage, current, temperature,
  state (charging, discharging, idle, or MOSFET off), alarm badges, signal strength, and
  how old the data is.
- **Scrolling:** the list scrolls when favorites outnumber the screen. Discovered devices sit below them.
- **Search button:** runs a time-limited scan (default 15 s). Any supported battery that isn't
  a favorite appears under "Discovered", showing its advertised name and live signal strength.
- **Long-press on a discovered device:** a context menu with *Add to favorites* (which prompts
  for a nickname and capacity) and *Identify* (a one-time read that shows its SOC and voltage,
  so you can tell which physical battery it is).
- **Long-press on a favorite:** *Rename*, *Refresh now*, *Move*, *Remove from favorites*.
- **Tap a favorite:** opens [battery detail](battery-detail.md).
- **Refresh all (⟳):** starts a polling cycle right away.

## Signal strength
- Signal strength (RSSI) comes from the last advertisement or connection, and is shown as bars plus a dBm value.
- When the data comes from the Linux hub, the signal strength is measured *at the hub*, and
  the card shows a small hub icon so that's clear.

## Edge cases
- A favorite isn't found during a cycle: keep the cached data, mark it stale, and show "not seen
  since …".
- Another client holds the battery's connection: show "busy" and try again next cycle.
- Two batteries with identical names: tell them apart by MAC address. *Identify* helps match them to the physical batteries.
