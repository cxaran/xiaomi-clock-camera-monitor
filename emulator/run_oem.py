#!/usr/bin/env python3
"""Run one isolated PC OEM emulator. No USB or physical-device target is used."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import platform
import socket
import subprocess
import time

import compose

HERE = Path(__file__).resolve().parent
NAME = "x04g_oem_camera_lab"
CONSOLE = 5602
SERVER = 5102
SERIAL = "emulator-5602"


def free_port(port):
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", port))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--images", type=Path, required=True, help="images.json from build_images.py")
    parser.add_argument("--engine", type=Path, required=True)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--adb-private-key", type=Path, required=True, help="Your local key, corresponding to the seed .pub")
    parser.add_argument("--state", type=Path, required=True, help="NEW writable AVD/session directory")
    parser.add_argument("--headless", action="store_true")
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise RuntimeError("This pinned engine is Linux x86_64; use WSL2 on Windows")
    state = args.state.absolute()
    if state.exists() or state.is_symlink() or not state.parent.is_dir():
        raise ValueError("State directory must be NEW; existing AVDs are preserved")
    free_port(CONSOLE)
    free_port(CONSOLE + 1)
    free_port(SERVER)
    manifest = json.loads(args.images.read_text())
    if manifest["schema"] != "x04g.oem-emulator-images.v1":
        raise ValueError("Unexpected image manifest")
    image_root = args.images.resolve().parent
    for row in manifest["files"]:
        path = compose.inside(image_root, row["path"])
        if path.is_symlink() or not path.is_file() or path.stat().st_size != row["bytes"] or compose.digest(path) != row["sha256"]:
            raise ValueError("Image hash mismatch")
    pins = json.loads((HERE / "recipe/engine-pins.json").read_text())
    engine = args.engine.resolve(strict=True)
    for row in pins["files"]:
        path = engine / row["name"]
        if path.is_symlink() or compose.digest(path) != row["sha256"]:
            raise ValueError("Engine hash mismatch")
    adb = args.adb.resolve(strict=True)
    key = args.adb_private_key.resolve(strict=True)
    if not adb.is_file() or not os.access(adb, os.X_OK) or not key.is_file():
        raise ValueError("Executable Linux adb and the corresponding private key are required")
    state.mkdir(mode=0o700)
    sdk_root = state / "sdk"
    (sdk_root / "platforms").mkdir(parents=True)
    (sdk_root / "platform-tools").mkdir()
    (sdk_root / "platform-tools/adb").symlink_to(adb)
    avd_home = state / "avd"
    avd_home.mkdir()
    datadir = avd_home / (NAME + ".avd")
    datadir.mkdir()
    sysdir = image_root / "sysdir"
    config = {"AvdId": NAME, "avd.ini.encoding": "UTF-8", "abi.type": "arm64-v8a",
              "hw.cpu.arch": "arm64", "hw.cpu.model": "cortex-a53", "hw.cpu.ncore": "1",
              "hw.ramSize": "1024", "hw.lcd.width": "800", "hw.lcd.height": "480",
              "hw.lcd.density": "240", "hw.gpu.enabled": "yes", "hw.gpu.mode": "swiftshader_indirect",
              "hw.keyboard": "yes", "hw.sensors.light": "yes", "hw.sensors.proximity": "yes",
              "disk.dataPartition.size": "2G", "image.sysdir.1": str(sysdir) + "/",
              "tag.id": "default", "tag.display": "Default", "target": "android-29"}
    (datadir / "config.ini").write_text("".join(name + "=" + value + "\n" for name, value in config.items()))
    (avd_home / (NAME + ".ini")).write_text("avd.ini.encoding=UTF-8\npath=" + str(datadir) + "\ntarget=android-29\n")
    env = os.environ.copy()
    for name in ("LD_PRELOAD", "LD_LIBRARY_PATH", "ADB_TRACE", "ANDROID_LOG_TAGS"):
        env.pop(name, None)
    env.update(HOME=str(state / "home"), ANDROID_AVD_HOME=str(avd_home),
               ANDROID_HOME=str(sdk_root), ANDROID_SDK_ROOT=str(sdk_root),
               ANDROID_USER_HOME=str(state / "android-home"), ANDROID_EMULATOR_HOME=str(state / "emulator-home"),
               ANDROID_SDK_HOME=str(state / "legacy-home"), ADB_VENDOR_KEYS=str(key),
               ANDROID_ADB_SERVER_PORT=str(SERVER), ADB_SERVER_PORT=str(SERVER),
               ADB_SERVER_SOCKET="tcp:127.0.0.1:" + str(SERVER), ANDROID_I_WANT_MY_TCG="yes",
               ANDROID_EMULATOR_LAUNCHER_DIR=str(engine),
               LD_LIBRARY_PATH=":".join(str(engine / name) for name in ("lib64", "lib64/qt/lib", "lib64/gles_swiftshader")))
    for name in ("home", "android-home", "emulator-home", "legacy-home"):
        (state / name).mkdir()
    if args.headless:
        env["QT_QPA_PLATFORM"] = "offscreen"
    server_env = env.copy()
    server_env.pop("ADB_SERVER_SOCKET", None)
    # An explicit hostname is treated as remote by older adb revisions, even
    # for loopback. The local daemon's default listener is loopback-only.
    subprocess.run([str(adb), "-P", str(SERVER), "start-server"],
                   env=server_env, check=True)
    command = [str(engine / "emulator-headless"), "-avd", NAME, "-port", str(CONSOLE),
               "-datadir", str(datadir), "-sysdir", str(sysdir),
               "-kernel", str(sysdir / "kernel-ranchu"), "-ramdisk", str(sysdir / "ramdisk.img"),
               "-system", str(sysdir / "system.img"), "-vendor", str(sysdir / "vendor.img"),
               "-initdata", str(sysdir / "userdata.img"), "-memory", "1024", "-lowram",
               "-no-boot-anim", "-no-audio", "-no-snapshot-load", "-no-snapshot-save",
               "-gpu", "swiftshader_indirect", "-cores", "1", "-accel", "off",
               "-prop", "qemu.x04g.lab.id=portable-oem-camera-lab", "-show-kernel", "-verbose"]
    # The selected upstream binary is headless. Use a viewer/scrcpy for display.
    command.append("-no-window")
    with (state / "emulator.stdout.log").open("xb") as out, (state / "emulator.stderr.log").open("xb") as err:
        child = subprocess.Popen(command, env=env, stdout=out, stderr=err, start_new_session=True)
    record = {"schema": "x04g.oem-emulator-session.v1", "pid": child.pid, "created_epoch": time.time(),
              "serial": SERIAL, "adb_server_port": SERVER, "console_port": CONSOLE,
              "adb": str(adb), "adb_private_key": str(key), "engine": str(engine),
              "image_manifest": str(args.images.resolve()), "state": str(state),
              "physical_device_accessed": False, "host_audio_enabled": False}
    (state / "session.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({"session": str(state / "session.json"), "serial": SERIAL, "adb_server_port": SERVER,
                      "pid": child.pid, "boot_verified": False}))


if __name__ == "__main__":
    main()
