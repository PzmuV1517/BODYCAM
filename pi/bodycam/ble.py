#!/usr/bin/env python3
"""BODYCAM BLE console: status, logs, Wi-Fi recovery and a shell over Bluetooth LE.

Nordic UART Service (NUS): the client writes text lines to RX and gets text back as TX
notifications. Every reply ends with a line "<<END rc nbytes>>" (nbytes = reply length, to detect loss).

Login is challenge/response so the secret never goes over the air:
    HELLO                       -> NONCE <hex>
    AUTH <hmac_sha256(secret, nonce) hex>  -> OK | DENIED
The secret is the Pi's Wi-Fi (AP) password, read fresh from NetworkManager on every login.

Commands after login:
    status | logs [n] | wifi restart | service restart | fan indicator on|off | fan test | reboot | sh <command>
"""
import asyncio
import hashlib
import hmac
import logging
import os
import queue
import secrets
import subprocess
import sys
import threading

import fan

# Pure-Python D-Bus (vendored): the Pi has no internet on its own AP, so no apt packages needed.
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor"))
from dbus_next import BusType, Variant  # noqa: E402
from dbus_next.aio import MessageBus  # noqa: E402
from dbus_next.constants import PropertyAccess  # noqa: E402
from dbus_next.service import ServiceInterface, dbus_property, method  # noqa: E402

log = logging.getLogger("bodycam-ble")

BLUEZ = "org.bluez"
ADAPTER = "/org/bluez/hci0"
APP_PATH = "/org/bodycam"
SVC_PATH = "/org/bodycam/nus"
RX_PATH = "/org/bodycam/nus/rx"
TX_PATH = "/org/bodycam/nus/tx"
ADV_PATH = "/org/bodycam/adv"

NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # client -> Pi (write)
NUS_TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # Pi -> client (notify)

AP_CONNECTION = "bodycam-ap"
SECRET_FILE = "/etc/bodycam/ble-secret"  # optional override of the Wi-Fi password
MAX_OUTPUT = 48 * 1024
SHELL_TIMEOUT = 60


# ---------------------------------------------------------------- commands
def run(cmd, timeout=SHELL_TIMEOUT):
    try:
        r = subprocess.run(cmd, shell=isinstance(cmd, str), capture_output=True, text=True, timeout=timeout)
        out = (r.stdout + r.stderr)
        if len(out) > MAX_OUTPUT:
            out = out[:MAX_OUTPUT] + f"\n[truncated at {MAX_OUTPUT} bytes]\n"
        return r.returncode, out
    except subprocess.TimeoutExpired:
        return 124, f"[timed out after {timeout}s]\n"
    except Exception as e:
        return 1, f"[error: {e}]\n"


def nm(*args):
    return run(["nmcli", *args], timeout=10)[1].strip()


def secret():
    try:
        with open(SECRET_FILE) as f:
            s = f.read().strip()
            if s:
                return s
    except OSError:
        pass
    return nm("-s", "-g", "802-11-wireless-security.psk", "connection", "show", AP_CONNECTION)


def status():
    active = nm("-t", "-f", "NAME,DEVICE", "connection", "show", "--active")
    ap_up = any(line.startswith(AP_CONNECTION + ":") for line in active.splitlines())
    ssid = nm("-g", "802-11-wireless.ssid", "connection", "show", AP_CONNECTION) or "?"
    chan = run("iw dev wlan0 info | awk '/channel/{print $2}'", 5)[1].strip() or "?"
    clients = run("iw dev wlan0 station dump | grep -c ^Station", 5)[1].strip() or "0"
    svc = run(["systemctl", "is-active", "bodycam"], 5)[1].strip()
    try:
        temp = int(open("/sys/class/thermal/thermal_zone0/temp").read()) // 1000
    except OSError:
        temp = 0
    fan_rpm = run("cat /sys/devices/platform/cooling_fan/hwmon/*/fan1_input 2>/dev/null", 5)[1].strip() or "?"
    up = run(["uptime", "-p"], 5)[1].strip()
    ips = run("hostname -I", 5)[1].strip()
    return (f"ap={'up' if ap_up else 'down'}\nssid={ssid}\nchannel={chan}\nclients={clients}\n"
            f"service={svc}\ntemp_c={temp}\nfan_rpm={fan_rpm}\nfan_indicator={'on' if fan.indicator_enabled() else 'off'}\n"
            f"night_exposure={'on' if fan.load_settings().get('night_exposure') else 'off'}\n"
            f"uptime={up}\nip={ips}\n")


def handle(session, line):
    """Returns (rc, text). Runs in a worker thread."""
    cmd, _, arg = line.strip().partition(" ")
    cmd = cmd.lower()
    if cmd == "hello":
        session.nonce = secrets.token_hex(16)
        session.authed = False
        return 0, f"NONCE {session.nonce}\n"
    if cmd == "auth":
        s = secret()
        if not s or not session.nonce:
            return 1, "DENIED\n"
        expect = hmac.new(s.encode(), session.nonce.encode(), hashlib.sha256).hexdigest()
        session.nonce = None  # one attempt per nonce: no replay, no brute force on one nonce
        session.authed = hmac.compare_digest(expect, arg.strip().lower())
        log.info("auth %s from %s", "OK" if session.authed else "DENIED", session.device)
        return (0, "OK\n") if session.authed else (1, "DENIED\n")
    if not session.authed:
        return 1, "LOGIN FIRST (HELLO, AUTH)\n"
    log.info("cmd from %s: %s", session.device, line.strip()[:200])
    if cmd == "status":
        return 0, status()
    if cmd == "logs":
        n = int(arg) if arg.strip().isdigit() else 50
        return run(f"journalctl -u bodycam -u bodycam-ble -n {min(n, 2000)} --no-pager -o short-iso")
    if cmd == "wifi" and arg.strip() == "restart":
        return run(["nmcli", "connection", "up", AP_CONNECTION], 30)
    if cmd == "service" and arg.strip() == "restart":
        return run(["systemctl", "restart", "bodycam"], 30)
    if cmd == "fan":
        if arg.strip() in ("indicator on", "indicator off"):
            fan.save_setting("fan_indicator", arg.strip().endswith("on"))
            return 0, f"fan startup signal {arg.strip().split()[1]} (video service picks it up on restart)\n"
        if arg.strip() == "test":
            fan.pulse()
            return 0, "fan pulsed twice\n"
        return 1, "usage: fan indicator on|off | fan test\n"
    if cmd == "reboot":
        threading.Timer(1.0, lambda: os.system("systemctl reboot")).start()
        return 0, "rebooting\n"
    if cmd == "sh":
        return run(arg)
    return 1, f"unknown command: {cmd}\n"


# ---------------------------------------------------------------- BlueZ GATT plumbing
class Session:
    def __init__(self, device):
        self.device = device
        self.buf = b""
        self.nonce = None
        self.authed = False
        self.mtu = 23


class GattService(ServiceInterface):
    def __init__(self):
        super().__init__("org.bluez.GattService1")

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s":
        return NUS

    @dbus_property(access=PropertyAccess.READ)
    def Primary(self) -> "b":
        return True

    @dbus_property(access=PropertyAccess.READ)
    def Characteristics(self) -> "ao":
        return [RX_PATH, TX_PATH]


class TxChar(ServiceInterface):
    """Notifications to the client, paced so BlueZ's queue never overflows."""

    def __init__(self, loop):
        super().__init__("org.bluez.GattCharacteristic1")
        self.loop = loop
        self.notifying = False
        self.value = b""
        self.out = asyncio.Queue()
        loop.create_task(self._drain())

    @dbus_property(access=PropertyAccess.READ)
    def Service(self) -> "o":
        return SVC_PATH

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s":
        return NUS_TX

    @dbus_property(access=PropertyAccess.READ)
    def Flags(self) -> "as":
        return ["notify"]

    @dbus_property(access=PropertyAccess.READ)
    def Value(self) -> "ay":
        return self.value

    @method()
    def StartNotify(self):
        self.notifying = True

    @method()
    def StopNotify(self):
        self.notifying = False

    def send_threadsafe(self, data, mtu):
        # ATT notification payload is MTU-3, but the MTU BlueZ reports can exceed what the link
        # actually carries (we saw a few bytes cut from each chunk), so keep a safety margin.
        size = max(20, mtu - 13)
        for i in range(0, len(data), size):
            self.loop.call_soon_threadsafe(self.out.put_nowait, data[i:i + size])

    async def _drain(self):
        while True:
            chunk = await self.out.get()
            if self.notifying:
                self.value = chunk
                self.emit_properties_changed({"Value": chunk})
            await asyncio.sleep(0.003)


class RxChar(ServiceInterface):
    def __init__(self, tx):
        super().__init__("org.bluez.GattCharacteristic1")
        self.tx = tx
        self.sessions = {}
        self.jobs = queue.Queue()
        threading.Thread(target=self._worker, daemon=True).start()

    @dbus_property(access=PropertyAccess.READ)
    def Service(self) -> "o":
        return SVC_PATH

    @dbus_property(access=PropertyAccess.READ)
    def UUID(self) -> "s":
        return NUS_RX

    @dbus_property(access=PropertyAccess.READ)
    def Flags(self) -> "as":
        return ["write", "write-without-response"]

    @method()
    def WriteValue(self, value: "ay", options: "a{sv}"):
        device = str(options["device"].value) if "device" in options else "?"
        s = self.sessions.get(device)
        if s is None:
            s = self.sessions[device] = Session(device)
            log.info("client %s", device)
        if "mtu" in options:
            s.mtu = int(options["mtu"].value)
        s.buf += bytes(value)
        while b"\n" in s.buf:
            line, s.buf = s.buf.split(b"\n", 1)
            if line.strip():
                self.jobs.put((s, line.decode(errors="replace")))
        if len(s.buf) > 4096:
            s.buf = b""

    def _worker(self):
        while True:
            s, line = self.jobs.get()
            rc, text = handle(s, line)
            if not text.endswith("\n"):
                text += "\n"
            body = text.encode()
            # byte count lets the client detect lost or cut notifications
            self.tx.send_threadsafe(body + f"<<END {rc} {len(body)}>>\n".encode(), s.mtu)


class Advertisement(ServiceInterface):
    def __init__(self):
        super().__init__("org.bluez.LEAdvertisement1")

    @method()
    def Release(self):
        log.info("advertisement released")

    @dbus_property(access=PropertyAccess.READ)
    def Type(self) -> "s":
        return "peripheral"

    @dbus_property(access=PropertyAccess.READ)
    def ServiceUUIDs(self) -> "as":
        return [NUS]

    @dbus_property(access=PropertyAccess.READ)
    def LocalName(self) -> "s":
        return "BODYCAM"


class ObjectManager(ServiceInterface):
    def __init__(self):
        super().__init__("org.freedesktop.DBus.ObjectManager")

    @method()
    def GetManagedObjects(self) -> "a{oa{sa{sv}}}":
        chrc = "org.bluez.GattCharacteristic1"
        return {
            SVC_PATH: {"org.bluez.GattService1": {
                "UUID": Variant("s", NUS), "Primary": Variant("b", True),
                "Characteristics": Variant("ao", [RX_PATH, TX_PATH])}},
            RX_PATH: {chrc: {
                "Service": Variant("o", SVC_PATH), "UUID": Variant("s", NUS_RX),
                "Flags": Variant("as", ["write", "write-without-response"])}},
            TX_PATH: {chrc: {
                "Service": Variant("o", SVC_PATH), "UUID": Variant("s", NUS_TX),
                "Flags": Variant("as", ["notify"])}},
        }


async def amain():
    loop = asyncio.get_running_loop()
    bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
    tx = TxChar(loop)
    bus.export(APP_PATH, ObjectManager())
    bus.export(SVC_PATH, GattService())
    bus.export(RX_PATH, RxChar(tx))
    bus.export(TX_PATH, tx)
    bus.export(ADV_PATH, Advertisement())

    intro = await bus.introspect("org.bluez", ADAPTER)
    obj = bus.get_proxy_object("org.bluez", ADAPTER, intro)
    props = obj.get_interface("org.freedesktop.DBus.Properties")
    await props.call_set("org.bluez.Adapter1", "Powered", Variant("b", True))
    await props.call_set("org.bluez.Adapter1", "Alias", Variant("s", "BODYCAM"))
    await obj.get_interface("org.bluez.GattManager1").call_register_application(APP_PATH, {})
    log.info("GATT registered")
    adv_mgr = obj.get_interface("org.bluez.LEAdvertisingManager1")
    await adv_mgr.call_register_advertisement(ADV_PATH, {})
    log.info("advertising as BODYCAM")

    # BlueZ does not always resume advertising after a client disconnects (the Pi then becomes
    # invisible after one session). Check every 3 s and re-register when no instance is active.
    async def keep_advertising():
        while True:
            await asyncio.sleep(3)
            try:
                active = (await props.call_get("org.bluez.LEAdvertisingManager1", "ActiveInstances")).value
                if active == 0:
                    try:
                        await adv_mgr.call_unregister_advertisement(ADV_PATH)
                    except Exception:
                        pass
                    await adv_mgr.call_register_advertisement(ADV_PATH, {})
                    log.info("advertising resumed")
            except Exception as e:
                log.warning("advertising check failed: %s", e)

    loop.create_task(keep_advertising())
    await bus.wait_for_disconnect()  # bluetoothd/dbus gone: exit, systemd restarts us


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(amain())


if __name__ == "__main__":
    main()
