# Architecture overview

**Status:** Draft. The stack isn't decided yet (see ADR 0001).

## Proposed layers
1. **UI:** screens and widgets
2. **Domain:** batteries, readings, alarms, and the rules that connect them
3. **Protocol drivers:** one driver per BMS variant, all behind a common interface
4. **BLE transport:** scanning, connections, retries, clean disconnects
5. **Storage:** a local database for batteries, settings, and history
6. **Background service:** a foreground service for monitoring and alarms

## Key design questions
- How many batteries can we keep connected at once, versus rotating connections?
- What should the polling intervals be, in the foreground and in the background?
- How should the driver interface separate read operations from write operations?
