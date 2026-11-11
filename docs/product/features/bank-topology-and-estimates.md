# Bank topology and runtime estimates

**Requirements:** FR-050 to FR-055
**Status:** Draft

## Banks
You define banks by hand in the app. The BMS can't detect how batteries are wired together.
- A bank is a tree: **parallel** groups and **series** strings, nested as needed (for example, 2S2P).
- A diagram editor shows the batteries as blocks, joined by series or parallel links.
- The app checks the wiring for mismatched nominal voltages in parallel and mismatched capacities in series, and warns about them.

## Combined stats
| Topology | Voltage | Capacity (Ah) | SOC |
|---|---|---|---|
| Parallel | average | sum | capacity-weighted average |
| Series | sum | minimum of the members | minimum of the members (the weakest battery limits the string) |

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
