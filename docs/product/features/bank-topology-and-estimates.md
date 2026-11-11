# Bank topology and runtime estimates

**Requirements:** FR-050 to FR-055
**Status:** Draft

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

## Estimates
- **Time to reserve** (discharging) = energy above the min-SOC threshold ÷ average discharge power
- **Time to max** (charging) = energy below the max-SOC threshold ÷ average charge power
- **Average power:** an exponentially weighted moving average over a configurable window (default 30 min).
  You can switch to "instantaneous" or a "same time yesterday" profile later.
- Show estimates with low confidence (greyed out) when there are too few samples or the load is changing quickly.
- When charging to 100%, the estimate is optimistic near the top because charge current tapers off.
  Apply a taper correction above about 90% (tunable).

## Display
- Bank summary row on the fleet overview
- Bank detail: the diagram, member cards, combined stats, and estimates, with bank-level control and profile actions
