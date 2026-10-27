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
MODULE_VERSION = "0.1.0-rc.2"
MODULE_VERSION_CODE = 4
OWN_PACKAGE = "org.x04g.home"
ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"


def apk_identity(apk: Path) -> dict:
    """Read literal identity attributes from the APK itself, not a sibling report.

    This small bounded AXML reader works in Linux/WSL without Windows aapt2.
    References and ambiguous attributes fail closed; our manifest uses literals.
    Signature verification remains the caller's responsibility.
    """
    with zipfile.ZipFile(apk) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == "AndroidManifest.xml"]
        if len(entries) != 1 or not 8 <= entries[0].file_size <= 1024 * 1024:
            raise ValueError("one_bounded_apk_manifest_required")
        data = archive.read(entries[0])
    def fail():
        raise ValueError("apk_manifest_identity_invalid")
    def uint16(offset):
        if offset < 0 or offset + 2 > len(data): fail()
        return struct.unpack_from("<H", data, offset)[0]
    def uint32(offset):
        if offset < 0 or offset + 4 > len(data): fail()
        return struct.unpack_from("<I", data, offset)[0]
    if uint16(0) != 3 or uint16(2) != 8 or uint32(4) != len(data): fail()
    strings = None
    identity = None
    offset = 8
    while offset < len(data):
        kind, header, size = uint16(offset), uint16(offset + 2), uint32(offset + 4)
        end = offset + size
        if header < 8 or size < header or end > len(data): fail()
        if kind == 1:  # ResStringPool
            if strings is not None or header < 28: fail()
            count, styles, flags = uint32(offset + 8), uint32(offset + 12), uint32(offset + 16)
            start = offset + uint32(offset + 20)
            styles_start = uint32(offset + 24)
            limit = offset + styles_start if styles_start else end
            if count > 65536 or styles > 65536 or offset + header + 4 * (count + styles) > start or not start <= limit <= end: fail()
            strings = []
            def length(pos, utf8):
                width = 1 if utf8 else 2
                if pos + width > limit: fail()
                first = data[pos] if utf8 else uint16(pos)
                pos += width
                high, shift = (0x80, 8) if utf8 else (0x8000, 16)
                if first & high:
                    if pos + width > limit: fail()
                    second = data[pos] if utf8 else uint16(pos)
                    return ((first & (high - 1)) << shift) | second, pos + width
                return first, pos
            for index in range(count):
                pos = start + uint32(offset + header + 4 * index)
                if not start <= pos < limit: fail()
                units, pos = length(pos, bool(flags & 0x100))
                byte_count, pos = length(pos, True) if flags & 0x100 else (units * 2, pos)
                terminator = 1 if flags & 0x100 else 2
                if pos + byte_count + terminator > limit or any(data[pos + byte_count:pos + byte_count + terminator]): fail()
                try:
                    value = data[pos:pos + byte_count].decode("utf-8" if flags & 0x100 else "utf-16-le")
                except UnicodeError:
                    fail()
                if len(value.encode("utf-16-le")) // 2 != units: fail()
                strings.append(value)
        elif kind == 0x102:  # ResXMLTree_startElement
            if strings is None or header != 16 or size < 36: fail()
            def string(index):
                if index >= len(strings): fail()
                return strings[index]
            if identity is None:
                if string(uint32(offset + 20)) != "manifest": fail()
                attribute_start, attribute_size, count = uint16(offset + 24), uint16(offset + 26), uint16(offset + 28)
                begin = offset + 16 + attribute_start
                if attribute_start < 20 or attribute_size != 20 or begin + attribute_size * count > end: fail()
                attrs = {}
                for index in range(count):
                    pos = begin + index * attribute_size
                    namespace, name = uint32(pos), string(uint32(pos + 4))
                    key = (None if namespace == 0xffffffff else string(namespace), name)
                    if key in attrs or uint16(pos + 12) != 8 or data[pos + 14] != 0: fail()
                    raw, value_type, value = uint32(pos + 8), data[pos + 15], uint32(pos + 16)
                    if raw != 0xffffffff and value_type == 3 and string(raw) != string(value): fail()
                    attrs[key] = (value_type, value)
                def literal(namespace, name, numeric=False, default=None):
                    item = attrs.get((namespace, name))
                    if item is None:
                        if default is not None: return default
                        fail()
                    value_type, value = item
                    if numeric:
                        if value_type not in (0x10, 0x11): fail()
                        return value
                    if value_type != 3: fail()
                    return string(value)
                package = literal(None, "package")
                low = literal(ANDROID_NAMESPACE, "versionCode", True)
                major = literal(ANDROID_NAMESPACE, "versionCodeMajor", True, 0)
                name = literal(ANDROID_NAMESPACE, "versionName")
                code = (major << 32) | low
                if package != OWN_PACKAGE or not 0 < code <= 0x7fffffffffffffff or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+\-]{0,95}", name): fail()
                identity = {"package": package, "version_code": code, "version_name": name}
        offset = end
    if offset != len(data) or identity is None: fail()
    return identity


def build_pins(apk_sha: str, signer_sha: str, apk_bytes: int, identity: dict) -> str:
    return ("package org.x04g.bootstrap; final class BuildPins {\n"
            'static final String OWN_APK_SHA="%s", OWN_SIGNER_SHA="%s";\n'
            "static final long OWN_APK_BYTES=%dL, OWN_VERSION_CODE=%dL;\n"
            "static final String OWN_VERSION_NAME=%s; }\n" %
            (apk_sha, signer_sha, apk_bytes, identity["version_code"], json.dumps(identity["version_name"], ensure_ascii=True)))


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
    apk_sha, apk_bytes = sha(apk), apk.stat().st_size
    verified = run([java, "-jar", sdk["apksigner"], "verify", "--verbose", "--print-certs", apk])
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", verified)
    if len(signers) != 1:
        raise RuntimeError("one_verified_signer_required")
    identity = apk_identity(apk)
    with zipfile.ZipFile(apk) as archive:
        dex_names = [n for n in archive.namelist() if re.fullmatch(r"classes[0-9]*\.dex", n)]
        if dex_names != ["classes.dex"]:
            raise RuntimeError("one_classes_dex_required")
        own_dex = archive.read("classes.dex")
    if sha(apk) != apk_sha or apk.stat().st_size != apk_bytes:
        raise RuntimeError("apk_changed_during_module_build")
    if not 112 <= len(own_dex) <= 4 * 1024 * 1024:
        raise RuntimeError("bounded_dex_required")
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="module-", dir=out) as temporary:
        stage = Path(temporary)
        classes, dex = stage / "classes", stage / "dex"
        classes.mkdir(); dex.mkdir()
        pins = stage / "BuildPins.java"
        pins.write_text(build_pins(apk_sha, signers[0].lower(), apk_bytes, identity), encoding="utf-8")
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
             "-fdebug-prefix-map=" + str(ROOT) + "=.",
             "-fdebug-prefix-map=" + str(ndk) + "=android-ndk-r20b",
             "-Wl,--exclude-libs,ALL", "-Wl,--gc-sections", "-Wl,--no-undefined", "-Wl,--strip-all",
             "-Wl,-z,relro,-z,now", "-Wl,-soname,armeabi-v7a.so",
             "-I", stage, "-I", HERE, HERE / "zygisk_entry.cpp", "-llog", "-o", "armeabi-v7a.so"], cwd=stage)
        data = native.read_bytes()
        if data[:6] != b"\x7fELF\x01\x01" or struct.unpack_from("<H", data, 18)[0] != 40:
            raise RuntimeError("ARM32_ELF_required")
        if any(str(path).encode() in data for path in (ROOT, ndk, stage)):
            raise RuntimeError("private_build_path_in_native")
        if sha(apk) != apk_sha or apk.stat().st_size != apk_bytes:
            raise RuntimeError("apk_changed_during_module_build")
        report = {"schema": "x04g.module.build.v1", "module_id": MODULE_ID,
                  "module_version": MODULE_VERSION, "module_version_code": MODULE_VERSION_CODE,
                  "apk_sha256": apk_sha, "apk_bytes": apk_bytes,
                  "apk_package": identity["package"], "apk_version_code": identity["version_code"],
                  "apk_version_name": identity["version_name"],
                  "apk_signer_sha256": signers[0].lower(), "payload_sha256": digest.hex(),
                  "payload_bytes": len(payload), "native_sha256": sha(native),
                  "stock_launcher_sha256": "1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21",
                  "initially_disabled": True, "uid_pin_included": False,
                  "neutral_build_paths_verified": True,
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
            add(archive, "module.prop", ("id=" + MODULE_ID + "\nname=X04G Camera Carousel\nversion=" + MODULE_VERSION
                                       + "\nversionCode=" + str(MODULE_VERSION_CODE) + "\n"
                                       "author=X04G Camera Monitor contributors\ndescription=Original carousel camera pages.\n").encode())
            add(archive, "customize.sh", customize, 0o755)
            add(archive, "disable", b""); add(archive, "skip_mount", b"")
            add(archive, "zygisk/armeabi-v7a.so", data, 0o755)
            add(archive, "payload.dex", payload)
            add(archive, "provenance.json", (json.dumps(report, indent=2) + "\n").encode())
            add(archive, "licenses/project-MIT.txt", (ROOT / "LICENSE.md").read_bytes())
            add(archive, "licenses/magisk-installer-GPL-3.0.txt", (HERE / "official/LICENSE-GPL-3.0.txt").read_bytes())
            zygisk_notice = (HERE / "official/zygisk.hpp").read_bytes().split(b"*/", 1)[0] + b"*/\n"
            add(archive, "licenses/zygisk-ISC.txt", zygisk_notice)
            add(archive, "licenses/nayuki-qr-MIT.txt", (ROOT / "app/src/org/x04g/home/qr/LICENSE-MIT.txt").read_bytes())
        report["zip_sha256"] = sha(output)
        (out / "build-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"module": output.name, "sha256": sha(output), "initially_disabled": True}))


if __name__ == "__main__":
    main()
