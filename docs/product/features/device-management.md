# Favorites and nicknames

**Requirements:** FR-001 to FR-006
**Status:** Draft

## Summary
Favorites are the batteries the app tracks, polls, and alarms on. Each one has a nickname.

## Favorite fields
- Nickname (required), with an optional color or icon
- MAC address and advertised name (both read-only)
- Protocol variant (detected automatically, can be overridden)
- Nominal capacity in Ah (prefilled from the BMS when it reports one) and nominal voltage
- Bank membership (see [bank-topology-and-estimates](bank-topology-and-estimates.md))
- Notes (for example, "garage shelf" or "the one with the dent")

## Behavior
- Add a favorite from the Discovered list (long-press), or add one manually by MAC address.
- Nicknames sync between the phone and the Linux hub when both are in use (see the architecture docs).
- Removing a favorite asks whether to keep or delete its history.
