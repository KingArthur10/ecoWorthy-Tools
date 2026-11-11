# Data model

Draft entities. Fields are TBD.

- **Battery:** id, nickname, MAC, BLE name, protocol variant, nominal capacity, cell count, icon, notes, sort order
- **Group:** id, name, member batteries
- **Reading:** battery id, timestamp, voltage, current, SOC, remaining Ah, cell voltages, temperatures, MOSFET state, protection flags
- **AlarmRule:** battery or group, metric, comparison, threshold, hysteresis, enabled, severity
- **AlarmEvent:** rule, timestamp, value, acknowledged
- **ParameterSnapshot:** battery, timestamp, raw parameter set (a backup taken before any write)
- **CommandLog:** battery, timestamp, command, result
