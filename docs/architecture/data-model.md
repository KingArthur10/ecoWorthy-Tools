# Data model

Draft. The fields aren't final.

- **Battery** (a favorite): id, nickname, MAC, BLE name, protocol variant, capacity (Ah), nominal voltage,
  cell count, color or icon, notes, sort order, controller node (hub or phone)
- **CachedState:** battery id, last reading, last-seen time, last signal strength, signal source (phone or hub), busy or stale flags
- **Reading:** battery id, timestamp, voltage, current, SOC, remaining Ah, cell voltages[], temperatures[],
  MOSFET state, balancing, protection flags, cycles
- **BatteryGroup** (implemented): id, name, wiring (PARALLEL | SERIES | NONE). A battery's `groupId` points to at most one group
- **Bank** (future, for nesting): id, name, root node. **BankNode** = {series | parallel, children: BankNode | Battery}
- **Profile:** id, name, max SOC, resume SOC, min SOC, reserve action, warning delay
- **ProfileAssignment:** battery or bank, profile, optional revert-to profile and revert time
- **AlarmRule:** battery or bank, metric, comparison, threshold, hysteresis, repeat limit, action (notify | cut discharge | cut charge), enabled
- **AlarmEvent:** rule, timestamp, value, acknowledged
- **ParameterSnapshot:** battery, timestamp, raw parameters (taken before any write)
- **CommandLog:** battery or bank, timestamp, command, source (user | profile | alarm), node, result
- **HubPairing:** hub id, address, pairing secret, last sync time
