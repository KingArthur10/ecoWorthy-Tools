# Safety rules for commands

Writing to a BMS can cut power to critical loads, disable protections, or damage hardware.

## Rules
1. Reading is always safe. Writing happens only on an explicit user action, or through an automation the user has turned on
   (a profile limit, or an alarm action).
2. Every manual state-changing command shows a confirmation that names the battery or bank by nickname.
3. **Series banks:** MOSFET actions apply to every member of the string together. One battery is never opened
   on its own in a series string (it would face nearly the full string voltage).
4. **Turning off discharge** names its effect plainly ("Loads on Garage A will lose power"). Done automatically,
   it gives a cancellable warning first.
5. Take a full parameter backup before any parameter write. Validate each value against the protocol's limits and
   LiFePO4 safe ranges.
6. Read back after each write to confirm it took effect.
7. Log every command, with its source (user, profile, or alarm) and the node it came from (phone or hub).
8. Only one node controls a given battery at a time (see ADR 0003).
9. Never offer to disable protections outside expert mode.
10. If enforcement stops (the app is killed or the hub is down), the battery falls back to its own BMS protection,
    which is always a safe state.
