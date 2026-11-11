# Polling and caching

## Polling cycle
- Runs every N minutes, and also on demand ("Refresh all" or "Refresh now"). N is a per-device setting
  (`StoredState.settings.pollIntervalMinutes`, default 10; choices are Manual only and 1, 2, 5, 10, 15, 30, 60, or 120 min).
  **Manual only** means the poller never touches the radio. Set it in the phone's Settings dialog, the hub dashboard's
  Settings, `PUT /api/settings`, or `battery-hub interval <minutes|manual>`. Changes apply within one 30 s pass.
- For each favorite in turn: scan briefly for it (which also records signal strength), connect, read all data,
  write the cache and history, **disconnect cleanly**, then move to the next.
- Batteries are polled one at a time, not all at once. That avoids hitting Android's connection limit and keeps power use down.
- Timeouts: give up on one battery after about 10 s and move on.
- A battery that's busy (another client holds it) is marked busy and retried next cycle.

## Adaptive interval
- Shorten the interval (for example, to 1–2 min) when:
  - a charge limit is active, the battery is charging, and SOC is within 5% of max
  - SOC is within 5% of the reserve
  - an alarm is active
- Lengthen it when every battery is idle (optional).

## Cache
- Each favorite's cache holds its last full reading, the time of that reading, and its signal strength.
- The UI always renders from the cache. Bluetooth reads update the cache, and the UI watches for those updates.

## Android specifics
- The poller runs in a foreground service (type `connectedDevice`) while monitoring is on.
- It needs a battery optimization exemption, so Doze mode doesn't delay cycles.
- **Open question:** what monitoring interval is acceptable in terms of phone battery drain?
