#!/usr/bin/env python3
"""BODYCAM Bluetooth console client (Mac). Works while the Mac stays on any Wi-Fi.

Usage (password = the Pi's BODYCAM Wi-Fi password, or env BODYCAM_PASS):
    tools/ble status
    tools/ble logs [N]
    tools/ble wifi restart | service restart | reboot
    tools/ble sh "<command>"
    tools/ble shell            interactive; type commands, Ctrl-D to quit
"""
import argparse
import asyncio
import getpass
import hashlib
import hmac
import os
import sys

from bleak import BleakClient, BleakScanner

NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"


class Console:
    def __init__(self, client):
        self.client = client
        self.buf = b""
        self.done = asyncio.Queue()

    def on_notify(self, _, data: bytearray):
        self.buf += bytes(data)
        while b"<<END " in self.buf:
            i = self.buf.index(b"<<END ")
            j = self.buf.find(b">>\n", i)
            if j < 0:
                break
            text = self.buf[:i]
            parts = self.buf[i + 6:j].split()
            rc = int(parts[0])
            self.buf = self.buf[j + 3:]
            out = text.decode(errors="replace")
            if len(parts) > 1 and int(parts[1]) != len(text):
                out += f"\n[BLE WARNING: got {len(text)} of {parts[1]} bytes, output is incomplete]\n"
            self.done.put_nowait((rc, out))

    async def call(self, line, timeout=90):
        data = (line + "\n").encode()
        size = max(20, self.client.mtu_size - 3)
        for i in range(0, len(data), size):
            await self.client.write_gatt_char(RX, data[i:i + size], response=True)
        return await asyncio.wait_for(self.done.get(), timeout)


async def connect(name, timeout):
    print(f"scanning for {name}...", file=sys.stderr)
    dev = await BleakScanner.find_device_by_filter(
        lambda d, ad: (d.name or ad.local_name or "") == name or NUS in (ad.service_uuids or []), timeout=timeout)
    if not dev:
        sys.exit(f"{name} not found over Bluetooth (in range? bodycam-ble running? Mac Bluetooth allowed for this terminal?)")
    client = BleakClient(dev)
    await client.connect()
    return client


async def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="+")
    ap.add_argument("--name", default="BODYCAM")
    ap.add_argument("--password", default=os.environ.get("BODYCAM_PASS"))
    ap.add_argument("--scan-timeout", type=float, default=15)
    a = ap.parse_args()
    password = a.password or getpass.getpass("BODYCAM Wi-Fi password: ")

    client = await connect(a.name, a.scan_timeout)
    try:
        con = Console(client)
        await client.start_notify(TX, con.on_notify)
        _, text = await con.call("HELLO")
        nonce = text.split()[1]
        proof = hmac.new(password.encode(), nonce.encode(), hashlib.sha256).hexdigest()
        rc, text = await con.call(f"AUTH {proof}")
        if rc != 0:
            sys.exit("login denied (wrong password?)")

        if a.command == ["shell"]:
            loop = asyncio.get_running_loop()
            while True:
                try:
                    line = await loop.run_in_executor(None, lambda: input("bodycam$ "))
                except EOFError:
                    break
                if line.strip():
                    rc, out = await con.call("sh " + line)
                    print(out, end="" if out.endswith("\n") else "\n")
            return 0
        cmd = " ".join(a.command)
        rc, out = await con.call(cmd)
        print(out, end="" if out.endswith("\n") else "\n")
        return rc
    finally:
        await client.disconnect()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
