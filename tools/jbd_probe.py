"""Read-only JBD BMS probe. Sends ONLY read requests 0x03, 0x04, 0x05."""
# Dev tool: read-only JBD probe. Usage: python jbd_probe.py out.json MAC [MAC...]  (needs: pip install bleak)
import asyncio, json, sys, time
from bleak import BleakClient, BleakScanner

NOTIFY = "0000ff01-0000-1000-8000-00805f9b34fb"
WRITE = "0000ff02-0000-1000-8000-00805f9b34fb"
READ_CMDS = {0x03: "basic", 0x04: "cells", 0x05: "hwver"}  # allowlist: reads only


def request(cmd):
    assert cmd in READ_CMDS
    chk = (0x10000 - (cmd + 0x00)) & 0xFFFF
    return bytes([0xDD, 0xA5, cmd, 0x00, chk >> 8, chk & 0xFF, 0x77])


def valid(frame):
    if len(frame) < 7 or frame[0] != 0xDD or frame[-1] != 0x77:
        return False
    n = frame[3]
    if len(frame) != n + 7:
        return False
    chk = (0x10000 - sum(frame[2:4 + n])) & 0xFFFF
    return chk == (frame[4 + n] << 8 | frame[5 + n])


def u16(b, i): return b[i] << 8 | b[i + 1]
def s16(b, i): v = u16(b, i); return v - 0x10000 if v & 0x8000 else v


def parse_basic(d):
    cells = d[21]; ntc = d[22]
    pd = u16(d, 10)
    return {
        "voltage_v": u16(d, 0) / 100, "current_a": s16(d, 2) / 100,
        "remaining_ah": u16(d, 4) / 100, "nominal_ah": u16(d, 6) / 100,
        "cycles": u16(d, 8),
        "production_date": f"{2000 + (pd >> 9)}-{(pd >> 5) & 0xF:02d}-{pd & 0x1F:02d}",
        "balance_bits": f"{u16(d, 12):016b}{u16(d, 14):016b}",
        "protection_bits": f"{u16(d, 16):016b}",
        "sw_version": f"{d[18] >> 4}.{d[18] & 0xF}", "soc_pct": d[19],
        "charge_fet_on": bool(d[20] & 1), "discharge_fet_on": bool(d[20] & 2),
        "cell_count": cells,
        "temps_c": [round((u16(d, 23 + 2 * i) - 2731) / 10, 1) for i in range(ntc)],
        "extra_bytes": d[23 + 2 * ntc:].hex(),
    }


async def probe(mac):
    out = {"mac": mac, "time": time.strftime("%Y-%m-%dT%H:%M:%S"), "raw": {}}
    dev = await BleakScanner.find_device_by_address(mac, timeout=15)
    if not dev:
        out["error"] = "not found"; return out
    out["name"] = dev.name
    buf = bytearray(); got = asyncio.Event()

    def on_notify(_, data):
        buf.extend(data)
        if buf and buf[-1] == 0x77 and len(buf) >= 7 and len(buf) >= buf[3] + 7:
            got.set()

    async with BleakClient(dev, timeout=20) as c:
        out["services"] = {s.uuid: [f"{ch.uuid} {','.join(ch.properties)}" for ch in s.characteristics]
                           for s in c.services}
        await c.start_notify(NOTIFY, on_notify)
        for cmd, name in READ_CMDS.items():
            for attempt in range(3):
                buf.clear(); got.clear()
                await c.write_gatt_char(WRITE, request(cmd), response=False)
                try:
                    await asyncio.wait_for(got.wait(), 3)
                except asyncio.TimeoutError:
                    continue
                if valid(buf):
                    break
            frame = bytes(buf)
            out["raw"][name] = frame.hex()
            if not valid(frame):
                out.setdefault("errors", []).append(f"{name}: invalid/no response")
                continue
            if frame[2] != 0:
                out.setdefault("errors", []).append(f"{name}: status {frame[2]}")
                continue
            d = frame[4:4 + frame[3]]
            if name == "basic":
                out["basic"] = parse_basic(d)
            elif name == "cells":
                out["cells_v"] = [u16(d, i) / 1000 for i in range(0, len(d), 2)]
            else:
                out["hwver"] = d.decode("ascii", "replace")
        await c.stop_notify(NOTIFY)
    # leaving the context manager disconnects cleanly
    return out


async def main(macs):
    results = []
    for mac in macs:
        try:
            r = await probe(mac)
        except Exception as e:
            r = {"mac": mac, "error": repr(e)}
        results.append(r)
        print(json.dumps(r, indent=1), flush=True)
        await asyncio.sleep(2)
    return results


if __name__ == "__main__":
    res = asyncio.run(main(sys.argv[2:]))
    json.dump(res, open(sys.argv[1], "w"), indent=1)
