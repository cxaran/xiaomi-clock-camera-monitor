#!/usr/bin/env python3
"""Verify the real StockBootstrap worker from a module's merged DEX, in PC only.

Unlike integrate.py, this does not call OemCarousel.install or skip the bridge.
It never installs a Zygisk module, changes validation flags or targets USB.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
import controls

OEM_SHA = "1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21"
PROCESS = "com.google.android.apps.mediashell"
ACTIVITY = "com.google.assistant.launcher/com.google.assistant.surfaces.clock.launcher.AssistantLauncherActivity"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", type=Path, required=True)
    parser.add_argument("--apk", type=Path, default=ROOT / "build/app/x04g-home.apk")
    parser.add_argument("--module", type=Path, default=ROOT / "build/integration/x04g-camera-carousel-disabled.zip")
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--module-sha256", required=True)
    parser.add_argument("--timeout-seconds", type=int, default=300)
    args = parser.parse_args()
    if os.name != "posix" or not 30 <= args.timeout_seconds <= 480:
        parser.error("Use Linux/WSL with a 30..480 second bounded verification")
    doc = json.loads(args.session.read_text())
    if doc.get("schema") != "x04g.oem-emulator-session.v1" or doc.get("serial") != "emulator-5602" or doc.get("adb_server_port") != 5102:
        parser.error("Only emulator-5602 on private server5102 is accepted")
    for value in (args.apk_sha256, args.module_sha256):
        if not re.fullmatch("[a-f0-9]{64}", value):
            parser.error("Expected artifact SHA256 values are required")
    if sha(args.apk) != args.apk_sha256 or sha(args.module) != args.module_sha256:
        parser.error("An input artifact differs from its expected SHA256")
    with zipfile.ZipFile(args.module) as archive:
        info = archive.getinfo("payload.dex")
        if not 112 <= info.file_size <= 4 * 1024 * 1024:
            parser.error("Bounded merged module DEX required")
        payload = archive.read(info)
        pins = json.loads(archive.read("provenance.json"))
    payload_sha = hashlib.sha256(payload).hexdigest()
    if pins.get("apk_sha256") != args.apk_sha256 or pins.get("apk_bytes") != args.apk.stat().st_size or pins.get("payload_sha256") != payload_sha:
        parser.error("Module provenance does not match its APK/DEX")
    if pins.get("apk_version_code") is None or pins.get("apk_version_name") is None:
        parser.error("Module provenance must contain the APK-derived version pins")
    java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else Path(shutil.which("java") or "/missing/java").parent.parent
    java, javac = java_home / "bin/java", java_home / "bin/javac"
    if not java.is_file() or not javac.is_file():
        parser.error("A Linux JDK17/21 with jdk.jdi is required")
    env = controls.session_environment(doc)
    prefix = [doc["adb"], "-H", "127.0.0.1", "-P", "5102", "-s", "emulator-5602"]
    deadline = time.monotonic() + args.timeout_seconds
    out = ROOT / "build/emulator-lab" / ("bridge-" + str(time.time_ns()))
    out.mkdir(parents=True)
    report = {"schema": "x04g.actual-bridge-virtual-check.v1", "apk_sha256": args.apk_sha256,
              "module_sha256": args.module_sha256, "payload_sha256": payload_sha,
              "physical_device_accessed": False, "RF_transmitted": False,
              "persistent_Zygisk_native_hook_tested": False, "messages": [], "ok": False}
    port, child, boot, pid = None, None, None, None

    def remaining(cap):
        value = min(cap, deadline - time.monotonic())
        if value <= 0:
            raise TimeoutError("overall_virtual_bridge_deadline")
        return value

    def call(args, cap=30):
        result = subprocess.run(prefix + args, env=env, capture_output=True, timeout=remaining(cap))
        if result.returncode or len(result.stdout) > 2 * 1024 * 1024:
            raise RuntimeError("bounded_emulator_command_failed")
        if args[0] == "emu":
            controls.accept_console_result(result)
        return result.stdout.decode(errors="replace").strip()

    def guard():
        nonlocal boot
        if call(["shell", "getprop", "ro.kernel.qemu"]) != "1" or call(["shell", "getprop", "qemu.x04g.lab.id"]) != "portable-oem-camera-lab":
            raise RuntimeError("physical_or_foreign_target_rejected")
        observed = call(["shell", "cat", "/proc/sys/kernel/random/boot_id"])
        if boot is None:
            boot = observed
        elif boot != observed:
            raise RuntimeError("emulator_boot_changed")

    try:
        guard()
        if call(["shell", "sha256sum", "/product/app/com.google.assistant.launcher/app.apk"]).split()[0] != OEM_SHA:
            raise RuntimeError("OEM_hash_mismatch")
        call(["install", "-r", str(args.apk.resolve())], 180)
        active = call(["shell", "pm", "path", "org.x04g.home"])
        match = re.fullmatch(r"package:(/data/app/org[.]x04g[.]home-[A-Za-z0-9_=+~-]+/base[.]apk)", active)
        if not match or call(["shell", "sha256sum", match.group(1)]).split()[0] != args.apk_sha256:
            raise RuntimeError("installed_APK_hash_mismatch")
        package = call(["shell", "dumpsys", "package", "org.x04g.home"])
        version_name = re.search(r"\bversionName=([^\s]+)", package).group(1)
        version_code = int(re.search(r"\bversionCode=(\d+)", package).group(1))
        if (version_name, version_code) != (pins["apk_version_name"], pins["apk_version_code"]):
            raise RuntimeError("installed_APK_version_mismatch")
        report["installed_APK"] = {"version_name": version_name, "version_code": version_code,
                                   "sha256": args.apk_sha256, "bytes": args.apk.stat().st_size}
        values = []
        for name in ("com.google.assistant.launcher", PROCESS):
            text = call(["shell", "cmd", "package", "list", "packages", "-U", name])
            match = re.fullmatch("package:" + re.escape(name) + r" uid:(1[0-9]{4})", text)
            if not match:
                raise RuntimeError("OEM_UID_not_unique")
            values.append(int(match.group(1)))
        if values[0] != values[1]:
            raise RuntimeError("shared_UID_differs")
        report["own_shared_UID_verified"] = True
        call(["shell", "am", "set-debug-app", "--persistent", PROCESS])
        call(["shell", "am", "force-stop", "com.google.assistant.launcher"])
        call(["shell", "am", "start", "-W", "-n", ACTIVITY], 150)
        call(["emu", "sensor", "set", "light", "500"])
        guard()
        pid = call(["shell", "pidof", PROCESS])
        if not re.fullmatch("[1-9][0-9]*", pid) or call(["shell", "cat", "/proc/" + pid + "/cmdline"]).rstrip("\0") != PROCESS:
            raise RuntimeError("OEM_process_not_unique")
        dex = out / "module-payload.dex"
        dex.write_bytes(payload)
        classes = out / "classes"
        classes.mkdir()
        result = subprocess.run([str(javac), "--release", "17", "--add-modules", "jdk.jdi", "-d", str(classes), str(HERE / "JdiBridgeProbe.java")],
                                capture_output=True, timeout=remaining(60))
        if result.returncode:
            raise RuntimeError("JDI_probe_compile_failed")
        port = call(["forward", "tcp:0", "jdwp:" + pid])
        if not re.fullmatch("[1-9][0-9]{3,4}", port):
            raise RuntimeError("forward_port_rejected")
        child = subprocess.Popen([str(java), "--add-modules", "jdk.jdi", "-cp", str(classes), "JdiBridgeProbe",
            "--port", port, "--uid", str(values[0]), "--dex", str(dex), "--dex-sha256", payload_sha],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        errors, actions = [], set()

        def collect():
            for line in child.stdout:
                if len(line) > 65536 or len(report["messages"]) > 12:
                    errors.append("output_limit")
                    continue
                try:
                    row = json.loads(line)
                    report["messages"].append(row)
                    if row.get("stage") == "armed":
                        action = row.get("action")
                        if action not in ("load", "diagnostic") or action in actions:
                            raise RuntimeError("trigger_identity")
                        actions.add(action)
                        guard()
                        if call(["shell", "pidof", PROCESS]) != pid:
                            raise RuntimeError("OEM_process_changed")
                        call(["shell", "input", "tap", "12", "460"], 60)
                except Exception as error:
                    errors.append(type(error).__name__)

        reader = threading.Thread(target=collect, daemon=True)
        reader.start()
        rc = child.wait(timeout=remaining(240))
        reader.join(timeout=5)
        stderr = child.stderr.read(65537)
        final = next((row for row in reversed(report["messages"]) if "ok" in row), {})
        diagnostic = json.loads(final.get("bootstrap_diagnostic_json", "{}"))
        carousel = json.loads(final.get("carousel_diagnostic_json", "{}"))
        report.update(real_bridge_flags={key: final.get(key) for key in ("verified", "oemIdentityVerified", "companionVerified", "runtime_uid_matches")},
                      actual_BuildPins={"version_name": final.get("pins_version_name"), "version_code": final.get("pins_version_code"), "apk_sha256": final.get("pins_apk_sha256")},
                      bootstrap_diagnostic=diagnostic, carousel_diagnostic=carousel,
                      same_loader_bridge_bootstrap=final.get("same_loader_bridge_bootstrap"),
                      errors=errors, stderr_present=bool(stderr), native_hook_not_simulated=True)
        report["ok"] = rc == 0 and final.get("ok") is True and not errors and diagnostic.get("state") == "INSTALLED" and carousel.get("active") is True and final.get("pins_version_code") == version_code and final.get("pins_version_name") == version_name and final.get("pins_apk_sha256") == args.apk_sha256
        guard()
    except Exception as error:
        report["error_type"] = type(error).__name__
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
            report["ok"] = False
        if port:
            try:
                done = subprocess.run(prefix + ["forward", "--remove", "tcp:" + port], env=env, capture_output=True, timeout=10)
                report["forward_removed"] = done.returncode == 0
            except Exception:
                report["forward_removed"] = False
            if not report["forward_removed"]:
                report["ok"] = False
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"ok": report["ok"], "report": (out / "report.json").relative_to(ROOT).as_posix()}))
    if not report["ok"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
