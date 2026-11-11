# Spike: BlueZ BLE from the JVM (2026-11-10)

**Question:** can a Kotlin/JVM hub reliably connect to, read, and cleanly disconnect from the JBD batteries through BlueZ?
**Answer: yes.** Use dbus-java 5.2.2 (native unix-socket transport) with hand-written minimal BlueZ interfaces.
The code is in `hub/` (`BluezBleLink.kt`). Run it with `./gradlew :hub:installDist && hub/build/install/hub/bin/hub --cycles 3 MAC...`.

## Setup
- Fedora 44, BlueZ 5.87, the laptop's built-in Bluetooth adapter (hci0), JDK 21 toolchain
- 5 batteries, polled one at a time: discover → connect → resolve services → StartNotify → read 0x03/0x04/0x05 → StopNotify → disconnect → confirm

## Results (3 runs × 3 cycles × 5 batteries = 45 polls)
| Run | Polls OK | Notes |
|---|---|---|
| 1 | 12/15 | **Bug:** a failed `Connect()` left BlueZ holding the battery (see below) |
| 2 (fixed) | 14/15 | 1 × `le-connection-abort-by-local` |
| 3 | 12/15 | 2 × connect aborts, 1 × not seen within 15 s (the battery at −93 to −100 dBm) |

After the fix, no run left any battery connected. The spike checks for this at the end of every run.

| Phase | Typical | Range | Notes |
|---|---|---|---|
| Scan + connect | 3–8 s | 1.8–21.6 s | Mostly waiting for an advertisement. Worst on weak-signal batteries |
| Read (3 commands) | **225 ms** | 225–674 ms, or about 3.2 s | About 3.2 s = one response timeout + retry. It still happens occasionally, even after waiting for `Notifying` (cause unknown; the retry covers it) |
| Disconnect + confirm | about 2.2 s | 1.8–2.8 s | |
| Whole poll | 7–14 s | 5–24 s | |

Signal strength from the PC's location: −59 dBm (…78:44) down to −100 dBm (…A2:52). Every failure was on a
battery weaker than about −80 dBm. The −60 dBm battery never failed.

## Findings that change the design
1. **A failed connect can still connect.** BlueZ may finish a connection in the background after
   `Connect()` has returned an error ("In Progress", `le-connection-abort-by-local`). The device then stops advertising,
   and *no other client can reach the battery*. **Rule: every failure path calls Disconnect and waits for
   `Connected == false`.** Before scanning, also clear any connection left over from a previous crashed run. Both are implemented.
2. **Range is the main source of failures, not software.** For a reliable hub, place the PC (or a USB
   Bluetooth adapter on an extension cable, ideally with an external antenna) closer to the batteries. Treat a failed poll as routine:
   retry it in the next cycle and show the data as stale. Don't raise an alarm for a single miss.
3. **Most of the time goes into scanning.** The hub should run *continuous passive LE scanning* and pause it only while
   connecting. That provides the live signal-strength readout for the overview screen at no extra cost, and lets the
   poller skip the per-poll discovery wait.
4. **Each poll occupies the battery for about 10 s.** That leaves a phone in direct mode plenty of room between hub polls,
   and confirms that poll-and-disconnect fits the one-client limit.
5. **A full cycle over 5 batteries takes about 50 s** (sequential). That's fine for a 10-minute interval. For the 1–2 minute adaptive
   interval near a charge limit, poll only the batteries that need it, not all five.

## Open
- The occasional missed first reply (one ~3 s retry). Possibly the first write is dropped right after the CCCD is enabled.
  Try a short delay after `Notifying`, or switch to `AcquireNotify`.
- Test a USB Bluetooth adapter placed near the batteries.
