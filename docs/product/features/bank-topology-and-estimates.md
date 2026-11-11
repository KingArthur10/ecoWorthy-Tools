# Bank topology and runtime estimates

**Requirements:** FR-050 to FR-055 (estimates also FR-015, FR-022)
**Status:** Draft. Groups and estimates are implemented; nesting isn't

## Implemented (2026-11-10): simple groups
- A group has a name and one wiring type: **Parallel**, **Series**, or **Not wired together** (for organization only).
- A battery belongs to at most one group (matching physical wiring). It's set from the battery's long-press menu → *Group…*
- The overview shows an **All batteries** total card (when there are 2+ favorites), then each group's card with its totals
  followed by its members, then the ungrouped batteries.
- Each battery card shows remaining and total capacity in Ah and Wh.
- Totals come from `aggregate()` in `core/.../domain/Groups.kt` (shared with the hub). Only batteries with readings count;
  partial totals are flagged ("only 4 of 5 have data"), along with the age of the oldest reading.
- Capacity uses the BMS's full-charge capacity when it reports one, otherwise the rated capacity. Wh = Ah × nominal voltage
  (cells × 3.2 V for LiFePO4).

## Banks (full design; nested topologies not yet built)
You define banks by hand in the app. The BMS can't detect how batteries are wired together.
- A bank is a tree: **parallel** groups and **series** strings, nested as needed (for example, 2S2P).
- A diagram editor shows the batteries as blocks, joined by series or parallel links.
- The app checks the wiring for mismatched nominal voltages in parallel and mismatched capacities in series, and warns about them.

## Combined stats
| Topology | Voltage | Capacity (Ah) | SOC |
|---|---|---|---|
| Parallel | average | sum | capacity-weighted average |
| Series | sum | minimum of the members | minimum of the members (the weakest battery limits the string) |
| Not wired together | n/a | sum | capacity-weighted average |

Energy (Wh) = the sum over members of capacity × nominal voltage × SOC.

## Implemented (2026-11-11): time estimates
Every battery card, group card, and the All batteries card shows a line such as
"⏱ Down to 20% in 6 h 20 min · around 9:40 PM" (phone, hub dashboard, and `battery-hub status`). The battery detail
screen adds the average current and how much history it covers. Code: `core/.../domain/Estimates.kt`; ADR 0004.
- **Thresholds:** each battery, each group, and All batteries has its own *discharge to* and *charge to* percentage
  (default 0% and 100%). Set them from the card's menu → *Thresholds…*, the detail screen, the dashboard, or
  `battery-hub thresholds <battery|group|all> --discharge-to N --charge-to N`. **They're for estimates only;
  nothing is sent to the BMS.** A group's thresholds are its own, independent of its members'.
- **Direction:** charging counts up to the charge threshold, discharging counts down to the discharge threshold. Idle
  (under 0.1 A) shows no estimate. Already past the threshold shows "At or below 20%" (or "Full" / "Empty").
- **Rolling rate:** the change in the BMS's remaining capacity (mAh) from the oldest sample in the last 30 min to the
  latest reading. The BMS counts current continuously, so this averages loads that cycle between polls (a fridge),
  which a snapshot of the current can't. With slow polling and no sample in the window, it reaches back up to 3 h
  for the most recent earlier one.
- **Rough (greyed, marked "rough"):** when the rate is the latest current alone (no usable earlier sample, under 2 min
  apart, or the flow just changed direction, e.g. the sun came up), covers under 10 min, or the latest current differs
  from the average by more than max(2 A, the average). A group is rough if any member is, or one has no reading.
- **Counting down:** the time left counts from now, not from the reading, so it shrinks between polls. If the
  projected time has passed, it says "Down to 20% by now, if the rate held".
- **Groups:** parallel and not-wired-together groups pool amp-hours and add currents. A series string carries one
  current (the members' average); a discharge ends when its first member reaches the threshold and a charge when its
  last one does (matching the string's SOC, which is its lowest member's). In a series string, a higher member's
  BMS may stop charging before that. All batteries is treated as not wired together, so its estimate is the net of
  every battery's current.
- **Not done yet:** a taper correction near full (estimates to 100% are optimistic in the last ~10%), a configurable
  window, and the "same time yesterday" profile.

## Estimates (design)
- **Time to reserve** (discharging) = energy above the min-SOC threshold ÷ average discharge power
- **Time to max** (charging) = energy below the max-SOC threshold ÷ average charge power
- **Average power:** ~~an exponentially weighted moving average~~ superseded by ADR 0004 (a rolling window over the
  BMS's remaining capacity, 30 min, not yet configurable).
  You can switch to "instantaneous" or a "same time yesterday" profile later.
- Show estimates with low confidence (greyed out) when there are too few samples or the load is changing quickly.
- When charging to 100%, the estimate is optimistic near the top because charge current tapers off.
  Apply a taper correction above about 90% (tunable).

## Display
- Bank summary row on the fleet overview
- Bank detail: the diagram, member cards, combined stats, and estimates, with bank-level control and profile actions
