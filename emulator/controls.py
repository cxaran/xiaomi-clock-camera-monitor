#!/usr/bin/env python3
"""Bounded controls for emulator-5602 on the private ADB server 5102 only."""
from pathlib import Path
import argparse
import json
import os
import re
import socket
import subprocess
import time


def session_environment(doc):
    """Use the same isolated console-token lookup directories as run_oem."""
    state = Path(doc["state"]).resolve(strict=True)
    env = os.environ.copy()
    env.update(HOME=str(state / "home"),
               ANDROID_EMULATOR_HOME=str(state / "emulator-home"),
               ANDROID_USER_HOME=str(state / "android-home"),
               ANDROID_AVD_HOME=str(state / "avd"),
               ANDROID_SDK_HOME=str(state / "legacy-home"),
               ANDROID_HOME=str(state / "sdk"), ANDROID_SDK_ROOT=str(state / "sdk"),
               ANDROID_ADB_SERVER_PORT="5102", ADB_SERVER_PORT="5102",
               ADB_VENDOR_KEYS=doc["adb_private_key"],
               ADB_SERVER_SOCKET="tcp:127.0.0.1:5102")
    return env


def accept_console_result(result):
    """adb emu can exit zero even when the console returns a KO."""
    text = (result.stdout + b"\n" + result.stderr).decode(errors="replace")
    if result.returncode != 0 or re.search(r"(?im)^\s*(?:KO|ERROR)(?=\s|:|$)", text):
        raise RuntimeError("The emulator console rejected the operation; no automatic retry")
    if not re.search(r"(?m)^\s*OK(?:\s*$|:)", text):
        raise RuntimeError("The emulator console did not confirm OK; outcome not accepted")
    return result


def private_server_listening():
    try:
        with socket.create_connection(("127.0.0.1", 5102), timeout=0.2):
            return True
    except ConnectionRefusedError:
        return False
    except OSError:
        return None


def device_rows(stdout):
    lines = stdout.decode(errors="strict").splitlines()
    if not lines or lines[0].strip() != "List of devices attached":
        raise ValueError("Unexpected private ADB device list")
    rows = []
    for line in lines[1:]:
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) < 2 or parts[1] not in ("device", "offline", "unauthorized", "recovery",
                "bootloader", "sideload", "host", "connecting", "authorizing"):
            raise ValueError("Unrecognized private ADB transport row")
        rows.append(parts[0])
    return rows


def close_idle_private_server(doc, env, *, runner=subprocess.run,
                              probe=private_server_listening, clock=time.monotonic,
                              sleep=time.sleep, wait_seconds=5):
    """Close only server5102 after its device list contains no transports.

    The emulator may take a moment to disappear after console kill. Another
    transport always keeps the server alive; a malformed/failed query never
    becomes a false empty device list. No RF, shell or other ADB server is used.
    """
    prefix = [doc["adb"], "-H", "127.0.0.1", "-P", "5102"]
    deadline = clock() + wait_seconds
    while True:
        try:
            result = runner(prefix + ["devices"], env=env, capture_output=True,
                            timeout=2, check=False)
            if result.returncode:
                return {"server_port": 5102, "status": "CLOSED" if probe() is False else "INDETERMINATE",
                        "server_kill_sent": False}
            rows = device_rows(result.stdout)
        except (ValueError, UnicodeError, OSError, subprocess.TimeoutExpired):
            return {"server_port": 5102, "status": "INDETERMINATE", "server_kill_sent": False}
        others = [serial for serial in rows if serial != "emulator-5602"]
        if others:
            return {"server_port": 5102, "status": "KEPT_OTHER_TRANSPORTS",
                    "other_transport_count": len(others), "server_kill_sent": False}
        if "emulator-5602" not in rows:
            break
        if clock() >= deadline:
            return {"server_port": 5102, "status": "KEPT_EMULATOR_CONNECTED", "server_kill_sent": False}
        sleep(0.1)
    local_env = env.copy()
    for name in ("ADB_SERVER_SOCKET", "ADB_SERVER_ADDRESS", "ANDROID_ADB_SERVER_ADDRESS"):
        local_env.pop(name, None)
    try:
        result = runner([doc["adb"], "-P", "5102", "kill-server"], env=local_env,
                        capture_output=True, timeout=3, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return {"server_port": 5102, "status": "INDETERMINATE", "server_kill_sent": True}
    end = clock() + 2
    while result.returncode == 0:
        if probe() is False:
            return {"server_port": 5102, "status": "CLOSED", "server_kill_sent": True}
        if clock() >= end:
            break
        sleep(0.1)
    return {"server_port": 5102, "status": "INDETERMINATE", "server_kill_sent": True}


def print_server_result(report):
    print(json.dumps(report))
    if report["status"] == "KEPT_OTHER_TRANSPORTS":
        print("El servidor ADB privado 5102 se conserva porque contiene otros transportes; no se cerró.")
    elif report["status"] == "KEPT_EMULATOR_CONNECTED":
        print("El emulador aún figura conectado; se conserva el servidor 5102. Puede repetir stop-server después.")
    elif report["status"] == "INDETERMINATE":
        print("No se confirmó el cierre del servidor privado 5102. Revise la sesión antes de arrancar otra.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", type=Path, required=True)
    parser.add_argument("operation", choices=("status", "wifi", "light", "tap", "swipe", "capture", "launcher", "stop", "stop-server"))
    parser.add_argument("values", nargs="*")
    args = parser.parse_args()
    doc = json.loads(args.session.read_text())
    if doc["schema"] != "x04g.oem-emulator-session.v1" or doc["serial"] != "emulator-5602" or doc["adb_server_port"] != 5102:
        raise ValueError("Only the isolated portable PC profile is allowed")
    env = session_environment(doc)
    if args.operation == "stop-server":
        if args.values:
            raise ValueError("stop-server accepts no extra values")
        print_server_result(close_idle_private_server(doc, env, wait_seconds=0))
        return
    prefix = [doc["adb"], "-H", "127.0.0.1", "-P", "5102", "-s", "emulator-5602"]
    def call(values, timeout=60):
        result = subprocess.run(prefix + values, env=env, capture_output=True, timeout=timeout, check=True)
        return accept_console_result(result) if values and values[0] == "emu" else result
    identity = call(["shell", "getprop", "qemu.x04g.lab.id"]).stdout.decode().strip()
    if identity != "portable-oem-camera-lab":
        raise ValueError("The PC profile marker differs; no input sent")
    if args.operation == "status":
        result = call(["shell", "getprop ro.build.version.sdk; getprop ro.product.cpu.abi; getprop sys.boot_completed; getenforce; wm size; wm density; getprop ro.config.low_ram; getprop dalvik.vm.heapgrowthlimit; getprop dalvik.vm.heapsize"])
    elif args.operation == "wifi":
        # AndroidWifi is the open, simulated AP supplied by the pinned engine.
        # This invokes the genuine OEM service, without Home/TOS/READY changes.
        if args.values:
            raise ValueError("wifi accepts only the fixed simulated AndroidWifi network")
        result = call(["shell", "am", "startservice", "-n",
                       "com.google.assistant.core/com.google.assistant.core.wifisetup.WifiSetupService",
                       "-a", "WifiSetupService.Connect", "--es", "ssid", "AndroidWifi"])
    elif args.operation == "light":
        if len(args.values) != 1 or not 0 <= float(args.values[0]) <= 100000:
            raise ValueError("Supply light in lux, from 0 to 100000")
        result = call(["emu", "sensor", "set", "light", str(float(args.values[0]))])
    elif args.operation == "tap":
        if len(args.values) != 2:
            raise ValueError("tap X Y")
        x, y = map(int, args.values)
        if not 0 <= x < 800 or not 0 <= y < 480:
            raise ValueError("Coordinates must be inside 800x480")
        result = call(["shell", "input", "tap", str(x), str(y)])
    elif args.operation == "swipe":
        if len(args.values) not in (4, 5):
            raise ValueError("swipe X1 Y1 X2 Y2 [milliseconds]")
        x1, y1, x2, y2 = map(int, args.values[:4])
        duration = int(args.values[4]) if len(args.values) == 5 else 450
        if not (0 <= x1 < 800 and 0 <= x2 < 800 and 0 <= y1 < 480 and 0 <= y2 < 480 and 50 <= duration <= 5000):
            raise ValueError("Invalid gesture coordinates or duration")
        result = call(["shell", "input", "swipe", *map(str, (x1, y1, x2, y2, duration))], timeout=120)
    elif args.operation == "capture":
        if len(args.values) != 1:
            raise ValueError("capture NEW.png")
        destination = Path(args.values[0])
        if destination.exists() or destination.suffix.lower() != ".png":
            raise ValueError("Choose a NEW PNG path")
        result = call(["exec-out", "screencap", "-p"], timeout=120)
        if not result.stdout.startswith(b"\x89PNG\r\n\x1a\n") or len(result.stdout) > 8 * 1024 * 1024:
            raise ValueError("Invalid or oversized PNG")
        with destination.open("xb") as stream:
            stream.write(result.stdout)
        print(json.dumps({"capture": str(destination), "bytes": len(result.stdout)}))
        return
    elif args.operation == "launcher":
        result = call(["shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-n",
                       "com.google.assistant.launcher/com.google.assistant.surfaces.clock.launcher.AssistantLauncherActivity"], timeout=120)
    else:
        result = call(["emu", "kill"])
    print(result.stdout.decode(errors="replace").strip())
    if args.operation == "stop":
        print_server_result(close_idle_private_server(doc, env))


if __name__ == "__main__":
    main()
