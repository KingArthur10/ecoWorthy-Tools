# Control capabilities by BMS variant

Researched 2026-11-10. **Verify every row against the real batteries** before relying on it.

| Capability | JBD (`DP04S…`, `JBD-…`) | BW02 (`ECO-WORTHY…`) | Redodo (`T-12…`) |
|---|---|---|---|
| Read SOC, voltage, current, cells, temps | Yes | Yes (aiobmsble) | Yes (aiobmsble) |
| Charge/discharge MOSFET toggle | **Yes**: `DD 5A E1 02 00 <mode> <chk> 77`, with mode 0 = both on, 1 = charge off, 2 = discharge off, 3 = both off | Likely: the official app offers it. Protocol not yet documented, so it needs a capture | Unknown |
| Read protection parameters | Yes (EEPROM registers) | Unknown | Unknown |
| Write protection parameters | Yes (factory mode, then register write). High risk | Unknown | Unknown |
| Charge current limit | **No** on standard boards. Over-current protection is a trip point, not a limiter. Some JBD boards have an optional current-limit module; check by reading your battery's parameters | Unknown | Unknown |
| Clear alarms / reset capacity | Partly (capacity registers) | Offered by the official app; needs a capture | Unknown |

## Max charge speed
Drop-in LiFePO4 BMSs switch current on or off. They don't regulate it. The **charger** sets the
current. Ways to control the charge rate:
1. A Bluetooth-controllable charger, solar controller, or DC-DC converter (for example Victron or Renogy). **Not applicable: the user has generic chargers.**
2. Set it on the charger by hand.
3. A JBD board with the current-limit module, if your batteries have one (to verify).

## How to fill in the unknowns
- Turn on Android's **Bluetooth HCI snoop log**, use each feature in the official ECO-WORTHY app,
  then open the log in Wireshark. Save the captures in `captures/` and decode them in the protocol docs.
- References: esphome-jbd-bms (syssi), aiobmsble, the JBD protocol PDF.
