#!/usr/bin/env python3
"""Temporary JDI integration in the exported PC emulator, never a physical clock.

Starts the original Activity explicitly for UI development. It does not assert
Google Home provisioning, Assistant or Cast service readiness.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import threading
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ACTIVITY = "com.google.assistant.launcher/com.google.assistant.surfaces.clock.launcher.AssistantLauncherActivity"
PROCESS = "com.google.android.apps.mediashell"
OEM_SHA = "1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", type=Path, required=True)
    parser.add_argument("--apk", type=Path, default=ROOT / "build/app/x04g-home.apk")
    parser.add_argument("--capture", action="store_true")
    parser.add_argument("mode", choices=("install", "attach", "status", "remove", "selectclock", "selectgate", "selectcamera"))
    args = parser.parse_args()
    if os.name != "posix":
        parser.error("Use Linux/WSL and a JDK17/21 with the jdk.jdi module.")
    doc = json.loads(args.session.read_text())
    if doc.get("schema") != "x04g.oem-emulator-session.v1" or doc.get("serial") != "emulator-5602" or doc.get("adb_server_port") != 5102:
        parser.error("Only the isolated emulator-5602 / server5102 profile is accepted.")
    java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
    java = java_home / "bin/java" if java_home else Path(shutil.which("java") or "/missing/java")
    javac = java.with_name("javac")
    if not java.is_file() or not javac.is_file():
        parser.error("A native JDK17/21 with java and javac is required; set JAVA_HOME.")
    env = dict(os.environ, ADB_VENDOR_KEYS=doc["adb_private_key"], ADB_SERVER_SOCKET="tcp:127.0.0.1:5102")
    prefix = [doc["adb"], "-H", "127.0.0.1", "-P", "5102", "-s", "emulator-5602"]
    boot = None

    def call(command, timeout=90, binary=False):
        result = subprocess.run(prefix + command, env=env, stdin=subprocess.DEVNULL,
                                capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError("emulator_adb_command_failed")
        if len(result.stdout) > (8 * 1024 * 1024 if binary else 1024 * 1024):
            raise RuntimeError("emulator_adb_output_limit")
        return result.stdout if binary else result.stdout.decode("utf-8", "replace").strip()

    def guard():
        nonlocal boot
        if call(["shell", "getprop", "ro.kernel.qemu"]) != "1":
            raise RuntimeError("physical_device_rejected")
        if call(["shell", "getprop", "qemu.x04g.lab.id"]) != "portable-oem-camera-lab":
            raise RuntimeError("wrong_lab_profile")
        observed = call(["shell", "cat", "/proc/sys/kernel/random/boot_id"])
        if boot is None:
            boot = observed
        elif boot != observed:
            raise RuntimeError("emulator_boot_changed")

    guard()
    apk = args.apk.resolve(strict=True)
    local_sha = sha(apk)
    if call(["shell", "sha256sum", "/product/app/com.google.assistant.launcher/app.apk"]).split()[0] != OEM_SHA:
        raise RuntimeError("stock_launcher_version_rejected")
    if args.mode == "install":
        call(["install", "-r", str(apk)], timeout=180)
        # LAB ONLY: enables JDWP for the original shared process on this AVD.
        call(["shell", "am", "set-debug-app", "--persistent", PROCESS])
        call(["shell", "am", "force-stop", "com.google.assistant.launcher"])
        call(["shell", "am", "start", "-W", "-n", ACTIVITY], timeout=180)
    guard()
    remote = call(["shell", "pm", "path", "org.x04g.home"])
    match = re.fullmatch(r"package:(/data/app/org[.]x04g[.]home-[A-Za-z0-9_=+~\-]+/base[.]apk)", remote)
    if not match:
        raise RuntimeError("companion_path_rejected")
    remote = match.group(1)
    if call(["shell", "sha256sum", remote]).split()[0] != local_sha:
        raise RuntimeError("installed_companion_hash_differs")
    pid = call(["shell", "pidof", PROCESS])
    if not re.fullmatch(r"[1-9][0-9]*", pid):
        raise RuntimeError("one_original_process_required")
    commandline = call(["shell", "cat", "/proc/" + pid + "/cmdline"]).rstrip("\0")
    if commandline != PROCESS:
        raise RuntimeError("original_process_identity_changed")
    # `adb jdwp` is a tracking stream in some platform-tools versions. Do not
    # wait for EOF: the exact-PID forward and JDI handshake validate this endpoint.
    out = ROOT / "build/emulator-lab" / (args.mode + "-" + str(time.time_ns()))
    classes = out / "classes"; classes.mkdir(parents=True)
    sources = [HERE / "JdiHome.java", HERE / "JdiHomeSelect.java"]
    compile_result = subprocess.run([str(javac), "--release", "17", "--add-modules", "jdk.jdi", "-encoding", "UTF-8",
                                     "-d", str(classes), *map(str, sources)], capture_output=True, timeout=60)
    if compile_result.returncode:
        raise RuntimeError("host_jdi_compile_failed:" + compile_result.stderr.decode("utf-8", "replace")[-3000:])
    port = call(["forward", "tcp:0", "jdwp:" + pid])
    if not re.fullmatch(r"[1-9][0-9]{3,4}", port):
        raise RuntimeError("dynamic_forward_port_rejected")
    child = None
    report = {"schema": "x04g.emulator.lab-integration.v1", "mode": args.mode,
              "apk_sha256": local_sha, "OEM_launcher_sha256": OEM_SHA,
              "lab_explicit_activity_entry": True, "Google_Home_or_Assistant_provisioning_verified": False,
              "physical_device_accessed": False, "messages": []}
    try:
        entry = "JdiHomeSelect" if args.mode.startswith("select") else "JdiHome"
        jdi_mode = "install" if args.mode == "attach" else args.mode
        child = subprocess.Popen([str(java), "--add-modules", "jdk.jdi", "-cp", str(classes), entry,
                                  "--mode", jdi_mode, "--host", "127.0.0.1", "--port", port,
                                  "--apk", remote, "--apk-sha256", local_sha], stdin=subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        errors = []
        triggered = []
        stderr_count = [0]

        def drain_error():
            for block in iter(lambda: child.stderr.read(4096), ""):
                stderr_count[0] += len(block)

        def collect():
            for line in child.stdout:
                if len(line) > 32768 or len(report["messages"]) > 20:
                    errors.append("jdi_output_limit"); continue
                try:
                    item = json.loads(line); report["messages"].append(item)
                    if item.get("stage") == "armed":
                        if triggered:
                            raise RuntimeError("duplicate_trigger_rejected")
                        guard()
                        if call(["shell", "pidof", PROCESS]) != pid:
                            raise RuntimeError("original_process_changed")
                        triggered.append(True)
                        call(["shell", "input", "tap", "12", "460"], timeout=90)
                except Exception as error:
                    errors.append(type(error).__name__)

        reader = threading.Thread(target=collect, daemon=True)
        error_reader = threading.Thread(target=drain_error, daemon=True)
        reader.start(); error_reader.start()
        rc = child.wait(timeout=190); reader.join(timeout=10); error_reader.join(timeout=5)
        final = next((row for row in reversed(report["messages"]) if "ok" in row), {})
        report.update(ok=rc == 0 and final.get("ok") is True and not errors,
                      errors=errors, stderr_present=bool(stderr_count[0]), trigger_taps=len(triggered))
        if args.capture and report["ok"]:
            time.sleep(2)
            png = call(["exec-out", "screencap", "-p"], binary=True, timeout=120)
            if not png.startswith(b"\x89PNG\r\n\x1a\n"):
                raise RuntimeError("invalid_emulator_capture")
            (out / "screen.png").write_bytes(png)
            report["capture_sha256"] = hashlib.sha256(png).hexdigest()
            report["capture_visually_reviewed"] = False
        guard()
    except Exception as error:
        report["ok"] = False
        report["error_type"] = type(error).__name__
        raise
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
            report["ok"] = False
            report["outcome"] = "INCOMPLETE_NO_AUTOMATIC_RETRY"
        try:
            call(["forward", "--remove", "tcp:" + port])
            report["forward_removed"] = True
        except Exception as error:
            report["ok"] = False
            report["forward_removed"] = False
            report["cleanup_error_type"] = type(error).__name__
        finally:
            (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"ok": report.get("ok"), "report": (out / "report.json").relative_to(ROOT).as_posix()}))
    if not report.get("ok"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
