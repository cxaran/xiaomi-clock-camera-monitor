"""Build a disabled, APK-pinned ARM32 Zygisk module. Does not contact a device."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
MODULE_ID = "x04g_home_bootstrap_direct01"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(argv: list, *, cwd: Path | None = None) -> str:
    result = subprocess.run([str(x) for x in argv], cwd=cwd, stdin=subprocess.DEVNULL,
                            capture_output=True, timeout=120)
    if result.returncode:
        raise RuntimeError("build_command_failed: " + result.stderr.decode("utf-8", "replace")[-4000:])
    return result.stdout.decode("utf-8", "replace")


def add(archive: zipfile.ZipFile, name: str, data: bytes, mode: int = 0o644) -> None:
    entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
    entry.create_system = 3
    entry.external_attr = (stat.S_IFREG | mode) << 16
    archive.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED)


def tools() -> dict:
    sys.path.insert(0, str(ROOT / "scripts"))
    from toolchain import resolve
    return resolve()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / "build/app/x04g-home.apk")
    parser.add_argument("--ndk", type=Path, required=True, help="Android NDK r20b root; build from Linux/WSL")
    parser.add_argument("--output", type=Path, default=ROOT / "build/integration")
    args = parser.parse_args()
    if os.name != "posix":
        parser.error("Use Linux or WSL; the native compiler recipe targets linux-x86_64 NDK r20b.")
    sdk = tools()
    java = Path(sdk["java"])
    ndk = args.ndk.resolve(strict=True)
    dependency = json.loads((ROOT / "tools/integration-dependencies.json").read_text("utf-8"))["ndk"]
    revision = (ndk / "source.properties").read_text(encoding="utf-8")
    if not re.search(r"Pkg\.Revision\s*=\s*20\.1\.5948944\b", revision):
        parser.error("The native recipe is pinned to NDK r20b (20.1.5948944).")
    cxx = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi29-clang++"
    if not cxx.is_file():
        parser.error("Linux x86_64 NDK compiler was not found.")
    compiler = ndk / dependency["clang_relative_path"]
    if not compiler.is_file() or sha(compiler) != dependency["clang_sha256"]:
        parser.error("NDK clang hash differs from the pinned r20b compiler.")
    apk = args.apk.resolve(strict=True)
    verified = run([java, "-jar", sdk["apksigner"], "verify", "--verbose", "--print-certs", apk])
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", verified)
    if len(signers) != 1:
        raise RuntimeError("one_verified_signer_required")
    with zipfile.ZipFile(apk) as archive:
        dex_names = [n for n in archive.namelist() if re.fullmatch(r"classes[0-9]*\.dex", n)]
        if dex_names != ["classes.dex"]:
            raise RuntimeError("one_classes_dex_required")
        own_dex = archive.read("classes.dex")
    if not 112 <= len(own_dex) <= 4 * 1024 * 1024:
        raise RuntimeError("bounded_dex_required")
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="module-", dir=out) as temporary:
        stage = Path(temporary)
        classes, dex = stage / "classes", stage / "dex"
        classes.mkdir(); dex.mkdir()
        pins = stage / "BuildPins.java"
        pins.write_text("package org.x04g.bootstrap; final class BuildPins {\n"
                        'static final String OWN_APK_SHA="%s", OWN_SIGNER_SHA="%s";\n'
                        "static final long OWN_APK_BYTES=%dL; }\n" %
                        (sha(apk), signers[0].lower(), apk.stat().st_size), encoding="utf-8")
        sources = sorted((HERE / "java").rglob("*.java"))
        run([java, "-jar", sdk["ecj"], "-1.8", "-bootclasspath",
             str(sdk["android"]) + os.pathsep + str(sdk["stubs"]),
             "-encoding", "UTF-8", "-d", classes, *sources, pins])
        jar = stage / "bridge.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            for path in sorted(classes.rglob("*.class")):
                add(archive, path.relative_to(classes).as_posix(), path.read_bytes())
        source_dex = stage / "own.dex"; source_dex.write_bytes(own_dex)
        run([java, "-cp", sdk["d8"], "com.android.tools.r8.D8", "--min-api", "29", "--lib", sdk["android"],
             "--output", dex, jar, source_dex])
        payload = bytearray((dex / "classes.dex").read_bytes())
        if not 112 <= len(payload) <= 4 * 1024 * 1024:
            raise RuntimeError("merged_dex_out_of_bounds")
        payload[12:32] = hashlib.sha1(payload[32:]).digest()
        struct.pack_into("<I", payload, 8, zlib.adler32(payload[12:]) & 0xffffffff)
        digest = hashlib.sha256(payload).digest()
        (stage / "build_pins.hpp").write_text(
            "#pragma once\nstatic constexpr size_t kDexBytes=%d;\nstatic constexpr uint8_t kDexSha256[32]={%s};\n" %
            (len(payload), ",".join("0x%02x" % b for b in digest)), encoding="ascii")
        native = stage / "armeabi-v7a.so"
        run([cxx, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC", "-shared", "-fno-exceptions",
             "-fno-rtti", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections", "-static-libstdc++",
             "-Wl,--exclude-libs,ALL", "-Wl,--gc-sections", "-Wl,--no-undefined", "-Wl,--strip-all",
             "-Wl,-z,relro,-z,now", "-I", stage, "-I", HERE, HERE / "zygisk_entry.cpp", "-llog", "-o", native])
        data = native.read_bytes()
        if data[:6] != b"\x7fELF\x01\x01" or struct.unpack_from("<H", data, 18)[0] != 40:
            raise RuntimeError("ARM32_ELF_required")
        report = {"schema": "x04g.module.build.v1", "module_id": MODULE_ID,
                  "apk_sha256": sha(apk), "apk_bytes": apk.stat().st_size,
                  "apk_signer_sha256": signers[0].lower(), "payload_sha256": digest.hex(),
                  "payload_bytes": len(payload), "native_sha256": sha(native),
                  "stock_launcher_sha256": "1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21",
                  "initially_disabled": True, "uid_pin_included": False,
                  "device_modified": False, "runtime_verified": False,
                  "sources": {p.relative_to(HERE).as_posix(): sha(p) for p in sources +
                              [HERE / "zygisk_entry.cpp", HERE / "uid_pin.hpp", HERE / "official/zygisk.hpp"]}}
        customize = b'''#!/system/bin/sh
[ "$MAGISK_VER_CODE" -ge 26000 ] || abort "Zygisk API4 requires Magisk26+"
[ "$ARCH" = arm ] || abort "X04G module is ARM32 only"
SKIPMOUNT=true
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/zygisk/armeabi-v7a.so" 0 0 0755
ui_print "Installed disabled. Set verified runtime UID before activation."
'''
        output = out / "x04g-camera-carousel-disabled.zip"
        with zipfile.ZipFile(output, "w") as archive:
            add(archive, "META-INF/com/google/android/update-binary", (HERE / "official/module_installer.sh").read_bytes(), 0o755)
            add(archive, "META-INF/com/google/android/updater-script", b"#MAGISK\n")
            add(archive, "module.prop", ("id=" + MODULE_ID + "\nname=X04G Camera Carousel\nversion=0.3\nversionCode=3\n"
                                       "author=X04G Camera Monitor contributors\ndescription=Original carousel camera pages.\n").encode())
            add(archive, "customize.sh", customize, 0o755)
            add(archive, "disable", b""); add(archive, "skip_mount", b"")
            add(archive, "zygisk/armeabi-v7a.so", data, 0o755)
            add(archive, "payload.dex", payload)
            add(archive, "provenance.json", (json.dumps(report, indent=2) + "\n").encode())
        report["zip_sha256"] = sha(output)
        (out / "build-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"module": output.name, "sha256": sha(output), "initially_disabled": True}))


if __name__ == "__main__":
    main()
