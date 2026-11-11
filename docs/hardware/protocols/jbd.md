# JBD / Xiaoxiang protocol

**Verified against real hardware:** No

## Reference implementations
- aiobmsble (Python): https://pypi.org/project/aiobmsble/
- TBD

## Transport
- Service UUID: `0000ff00-0000-1000-8000-00805f9b34fb` (confirmed in advertisements, 2026-11-10)
- Notify characteristic: `0xFF01` (per references; to verify)
- Write characteristic: `0xFF02` (per references; to verify)

## Frame format
TBD

## Read commands
| Command | Bytes | Response fields |
|---|---|---|

## Write and control commands
| Command | Bytes | Effect | Risk |
|---|---|---|---|
| MOSFET control | `DD 5A E1 02 00 <mode> <chk16> 77`, with mode 0 = both on, 1 = charge off, 2 = discharge off, 3 = both off | Charge and discharge switches | Medium. Cutting discharge drops the loads |

## Captured traces
Store raw captures in `docs/hardware/captures/` and link them here.
