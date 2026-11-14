"""Fan helpers: persistent BodyCam settings and the fan startup signal.

The startup signal briefly drops the fan's temperature thresholds below the current temperature
(fan goes to full speed), then restores them, so the normal quiet curve is untouched.
"""
import json
import os
import subprocess
import time

TZ = "/sys/class/thermal/thermal_zone0"
COOLING = "/sys/class/thermal/cooling_device0/cur_state"
SETTINGS = "/etc/bodycam/settings.json"
AP_CONNECTION = "bodycam-ap"


def load_settings():
    try:
        with open(SETTINGS) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_setting(key, value):
    d = load_settings()
    d[key] = value
    os.makedirs(os.path.dirname(SETTINGS), exist_ok=True)
    tmp = SETTINGS + ".tmp"
    with open(tmp, "w") as f:
        json.dump(d, f)
    os.replace(tmp, SETTINGS)


def indicator_enabled():
    return bool(load_settings().get("fan_indicator", True))


def ap_active():
    try:
        out = subprocess.run(["nmcli", "-t", "-f", "NAME", "connection", "show", "--active"],
                             capture_output=True, text=True, timeout=5).stdout
        return AP_CONNECTION in out.split()
    except Exception:
        return False


def _trips():
    return [int(open(f"{TZ}/trip_point_{i}_temp").read()) for i in range(1, 5)]


def _set_trips(values):
    for i, v in enumerate(values, 1):
        with open(f"{TZ}/trip_point_{i}_temp", "w") as f:
            f.write(str(v))


def pulse(times=2, on=1.2, off=1.5):
    """Audible signal: `times` full-speed bursts. Restores the thresholds afterwards."""
    try:
        saved = _trips()
    except OSError:
        saved = None
    try:
        for k in range(times):
            try:
                _set_trips((1000, 2000, 3000, 4000))  # all below current temp -> full speed
                time.sleep(on)
            except OSError:  # thresholds not writable: drive the cooling device directly
                end = time.monotonic() + on
                while time.monotonic() < end:
                    with open(COOLING, "w") as f:
                        f.write("4")
                    time.sleep(0.1)
                with open(COOLING, "w") as f:
                    f.write("0")
            if saved:
                _set_trips(saved)
            if k < times - 1:
                time.sleep(off)
    finally:
        if saved:
            try:
                _set_trips(saved)
            except OSError:
                pass


# ---------------------------------------------------------------- manual speed override
# Override = thermal control of the fan switched off and a fixed PWM written. The state lives in
# sysfs (thermal zone mode + pwm1), so the video server and the BLE console always agree.
import glob as _glob  # noqa: E402


def _pwm_path():
    paths = _glob.glob("/sys/devices/platform/cooling_fan/hwmon/hwmon*/pwm1")
    return paths[0] if paths else None


def rpm():
    try:
        return int(open(_glob.glob("/sys/devices/platform/cooling_fan/hwmon/hwmon*/fan1_input")[0]).read())
    except (OSError, IndexError, ValueError):
        return 0


def get_override():
    """0 = automatic (quiet curve), 1..100 = fixed speed in %."""
    try:
        if open(f"{TZ}/mode").read().strip() != "disabled":
            return 0
        return max(1, round(int(open(_pwm_path()).read()) * 100 / 255))
    except (OSError, TypeError, ValueError):
        return 0


def set_override(pct):
    """pct <= 0: back to automatic. 1..100: fixed speed."""
    pct = max(0, min(100, int(pct)))
    pwm = _pwm_path()
    if pct == 0:
        with open(f"{TZ}/mode", "w") as f:
            f.write("enabled")
        return
    with open(f"{TZ}/mode", "w") as f:
        f.write("disabled")
    if pwm:
        with open(pwm, "w") as f:
            f.write(str(round(pct * 255 / 100)))
