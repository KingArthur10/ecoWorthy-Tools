# Safety rules for commands

Writing to a BMS can disable protections, damage cells, or cut power to critical loads.

## Rules (draft)
1. Reading is always safe. Writing needs an explicit user action. Nothing writes automatically
   unless the user has set up that automation.
2. A state-changing command shows a confirmation that names the battery by its nickname.
3. Take a full parameter backup before any parameter write.
4. Validate every value against the protocol's limits and LiFePO4 safe ranges before sending.
5. Read back after each write to confirm it took effect.
6. Log every command (see CommandLog in the data model).
7. Never offer to disable protections in the normal UI. (TBD: an expert mode?)

## Open questions
- Should alarms be allowed to trigger actions automatically (for example, cutting discharge at 10% SOC)?
