# Command and control

**Requirements:** FR-030 to FR-036
**Status:** Draft. Which controls are available depends on the hardware; see
[control-capabilities](../../hardware/control-capabilities.md).

## Principle
The app shows only the controls that a battery's BMS actually supports, and says *why*
the rest are missing.

## Controls
| Control | Applies to | Mechanism | Notes |
|---|---|---|---|
| Charge on/off | Each battery or bank | BMS charge MOSFET | JBD: command 0xE1 is documented. BW02: the official app does this, but the protocol needs to be captured |
| Discharge on/off | Each battery or bank | BMS discharge MOSFET | Cuts power to loads; needs a strong confirmation |
| Charge limit (max SOC) | Each battery or bank | Enforced by the app (see the profiles spec) | |
| Discharge reserve (min SOC) | Each battery or bank | Enforced by the app | |
| Max charge current | n/a | **Out of scope.** The BMS can't regulate current, and the user's chargers are generic | FR-036 is deprecated |
| BMS parameters (read) | Each battery | JBD EEPROM registers | Shown in an "Advanced" section |
| BMS parameters (write) | Each battery | JBD EEPROM registers | Expert mode only, with a backup first. See [safety](../../safety.md) |
| Clear alarms, reset capacity | Each battery | BW02 (per the official app manual) | Needs a protocol capture |

## Series-bank rule
In a **series** bank, opening one battery's MOSFET breaks the whole string. That also puts
nearly the full string voltage across the open battery's MOSFETs, which can exceed what a
12 V BMS is rated for. So a charge or discharge toggle in a series bank applies to **every battery in the
string together**, and the app warns about it. Parallel banks can toggle one battery at a time.

## Safety
Every control action follows [safety.md](../../safety.md).
