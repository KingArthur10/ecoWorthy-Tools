# 0003: Hub mode and direct mode

**Status:** Proposed (updated 2026-11-10)
**Date:** 2026-11-10

## Context
- Each battery accepts one BLE connection at a time.
- **At home:** the Linux PC is a full command station (UI and control), and also serves the phone.
- **Camping:** the Linux PC may come along. There's **no internet and no infrastructure network**.

## Decision
1. **Hub mode:** whenever the phone can reach the hub over *any local IP link*, it talks to the
   hub, and the hub is the only BLE client.
2. **Local links that need no internet:**
   - Home Wi-Fi / LAN
   - **A phone hotspot:** Android can run a hotspot with no cellular service, and the PC joins it
   - **A hub access point:** the PC hosts its own Wi-Fi network (NetworkManager hotspot)
   Discovery runs over mDNS, with a manually set IP as a fallback.
3. **Direct mode:** with no hub reachable, the phone polls the batteries over BLE itself.
4. **Ownership handoff:** when the phone enters hub mode, it stops polling, and its pending
   profile or alarm duties move to the hub. When it leaves hub mode, it takes them back after a grace period
   (so a brief Wi-Fi drop doesn't cause both to poll).

## Consequences
- The hub needs a LAN API with authentication (a one-time pairing code, with no accounts).
- The phone needs sync logic and a clear indicator showing which mode it's in.
- The hub's Bluetooth range has to reach every battery (an extra USB BT adapter may be needed).
- **Open:** a phone-to-hub link over Bluetooth instead of Wi-Fi was considered and deferred. Wi-Fi is simpler and faster.
