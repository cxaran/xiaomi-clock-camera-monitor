#!/usr/bin/env python3
"""Build and inspect an authenticated USB boot image. Local files only.

Run under x86_64 Linux (WSL2 is supported). No device, ADB or network commands.
The input is the owner's own unmodified X04G boot partition, not a shared ROM.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import shutil
import struct
import subprocess
import zipfile

MAGISK_SHA256 = "ae1a02b1ab608a51d5bc9b323e0588d06d30d9987ac8da01f4710d76f705dccb"
MAGISKBOOT_SHA256 = "21d4fd18592c0c5160520bb991f8ded6d40bd901784f82276c3e303c2a8020a2"
HELPER_SHA256 = "3f137e13dcf79905bb86f93f8f3c0acefc8764384266fea3acd42450a1ebf689"
BOOT_BYTES = 16 * 1024 * 1024
RC = b"""on property:sys.boot_completed=1
    start x04g-adb-usb

service x04g-adb-usb /system/bin/sh ${MAGISKTMP}/x04g-adb-usb.sh ${MAGISKTMP}/x04g_adb.pub
    disabled
    oneshot
    user root
    group root
    seclabel u:r:magisk:s0
"""


def need(value, message):
    if not value:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def public_key(raw):
    """Strip the local host comment; validate Android's RSA2048 wire format."""
    text = raw.decode("ascii")
    need(len(text.splitlines()) == 1, "Public key must contain one line")
    tokens = text.strip().split()
    need(bool(tokens), "Empty public key")
    token = tokens[0]
    need(len(token) == 700, "Use adb keygen, not a PEM or private key")
    decoded = base64.b64decode(token, validate=True)
    need(len(decoded) == 524 and struct.unpack_from("<I", decoded)[0] == 64,
         "Unsupported ADB RSA structure")
    need(struct.unpack_from("<I", decoded, 520)[0] in (3, 65537),
         "Unsupported RSA exponent")
    return (token + "\n").encode("ascii")


def cpio(raw):
    """Parse newc, retaining all meaningful stock metadata and file bytes.

    c_ino can be renumbered by magiskboot serialization; it is recorded but is
    intentionally excluded from the preservation comparison.
    """
    pos, entries = 0, {}
    while pos + 110 <= len(raw):
        need(raw[pos:pos + 6] in (b"070701", b"070702"), "Unsupported CPIO")
        fields = tuple(int(raw[pos + 6 + i * 8:pos + 14 + i * 8], 16) for i in range(13))
        size, namesize = fields[6], fields[11]
        start = pos + 110
        need(namesize > 0 and start + namesize <= len(raw) and
             raw[start + namesize - 1] == 0, "Invalid CPIO name")
        name = raw[start:start + namesize - 1].decode("utf-8")
        start = (start + namesize + 3) & ~3
        if name == "TRAILER!!!":
            return entries
        need(name not in entries and start + size <= len(raw), "Invalid CPIO member")
        entries[name] = {"metadata": fields[1:], "data": raw[start:start + size]}
        pos = (start + size + 3) & ~3
    raise ValueError("CPIO trailer missing")


def build(args):
    need(os.name == "posix" and platform.machine().lower() in ("x86_64", "amd64"),
         "Run in x86_64 Linux/WSL2")
    boot, apk = args.boot.resolve(), args.magisk_apk.resolve()
    stock = boot.read_bytes()
    need(len(stock) == BOOT_BYTES and stock[:8] == b"ANDROID!", "Expected 16 MiB Android boot partition")
    need(sha(stock) == args.expect_stock_sha256.lower(), "Own stock backup SHA256 mismatch")
    need(struct.unpack_from("<I", stock, 40)[0] == 2, "Only Android boot header v2 is supported")
    need(sha(apk.read_bytes()) == MAGISK_SHA256, "Require official Magisk 26.1 APK")
    helper = Path(__file__).with_name("x04g-adb-usb.sh").read_bytes()
    need(sha(helper) == HELPER_SHA256, "Reviewed USB helper changed")
    key = public_key(args.public_key.read_bytes())
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    log = out / "build.log"
    tool = out / "magiskboot"
    with zipfile.ZipFile(apk) as archive:
        tool.write_bytes(archive.read("lib/x86_64/libmagiskboot.so"))
        for member, target in (("lib/armeabi-v7a/libmagiskinit.so", "magiskinit"),
                               ("lib/armeabi-v7a/libmagisk32.so", "magisk32"),
                               ("assets/stub.apk", "stub.apk")):
            (out / target).write_bytes(archive.read(member))
    need(sha(tool.read_bytes()) == MAGISKBOOT_SHA256, "magiskboot hash mismatch")
    tool.chmod(0o700)
    env = dict(os.environ, KEEPVERITY="true", KEEPFORCEENCRYPT="true", PATCHVBMETAFLAG="false")

    def run(directory, *command):
        result = subprocess.run([str(tool), *command], cwd=directory, env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=120, check=False)
        with log.open("ab") as stream:
            stream.write(result.stdout)
        need(result.returncode == 0, "magiskboot failed; inspect local build.log")

    shutil.copyfile(boot, out / "boot_stock.img")
    run(out, "unpack", "boot_stock.img")
    original = cpio((out / "ramdisk.cpio").read_bytes())
    need("init" in original and not any(n.startswith("overlay.d") or n.startswith(".backup")
                                        for n in original), "Require an unmodified stock ramdisk")
    need(any(n.startswith("fstab.") for n in original), "Stock fstab absent")
    shutil.copyfile(out / "ramdisk.cpio", out / "ramdisk_stock.cpio")
    original_parts = {n: sha((out / n).read_bytes()) for n in ("kernel", "dtb", "kernel_dtb")
                      if (out / n).exists()}
    need("kernel" in original_parts, "Stock kernel absent")
    (out / "x04g-adb-usb.sh").write_bytes(helper)
    (out / "x04g_adb.pub").write_bytes(key)
    (out / "init.adb_usb_local.rc").write_bytes(RC)
    config = ("KEEPVERITY=true\nKEEPFORCEENCRYPT=true\nPATCHVBMETAFLAG=false\n"
              "RECOVERYMODE=false\nSHA1=" + hashlib.sha1(stock).hexdigest() +
              "\nRANDOMSEED=0x" + secrets.token_hex(8) + "\n").encode("ascii")
    (out / "config").write_bytes(config)
    run(out, "compress=xz", "magisk32", "magisk32.xz")
    run(out, "compress=xz", "stub.apk", "stub.xz")
    run(out, "cpio", "ramdisk.cpio", "add 0750 init magiskinit",
        "mkdir 0750 overlay.d", "mkdir 0750 overlay.d/sbin",
        "add 0644 overlay.d/sbin/magisk32.xz magisk32.xz",
        "add 0644 overlay.d/sbin/stub.xz stub.xz",
        "add 0644 overlay.d/sbin/x04g_adb.pub x04g_adb.pub",
        "add 0750 overlay.d/sbin/x04g-adb-usb.sh x04g-adb-usb.sh",
        "add 0644 overlay.d/init.adb_usb_local.rc init.adb_usb_local.rc",
        "patch", "backup ramdisk_stock.cpio", "mkdir 000 .backup", "add 000 .backup/.magisk config")
    changed = cpio((out / "ramdisk.cpio").read_bytes())
    need(all(changed.get(n) == item for n, item in original.items() if n != "init"),
         "Stock ramdisk changed outside init")
    need(changed["overlay.d/sbin/x04g_adb.pub"]["data"] == key, "Public key changed in CPIO")
    # Repack from original compressed kernel, without recompressing it.
    (out / "kernel").unlink()
    run(out, "repack", "boot_stock.img", "boot_adb_usb.img")
    candidate = (out / "boot_adb_usb.img").read_bytes()
    need(len(candidate) == len(stock), "Boot partition size changed")
    need(all(stock[a:b] == candidate[a:b] for a, b in ((0, 16), (20, 576), (608, 1660))),
         "Boot header or command line changed")
    pagesize = struct.unpack_from("<I", stock, 36)[0]
    kernelsize = struct.unpack_from("<I", stock, 8)[0]
    need(stock[pagesize:pagesize + kernelsize] == candidate[pagesize:pagesize + kernelsize],
         "Compressed kernel/appended DTB changed")
    verify = out / "verify"
    verify.mkdir()
    run(verify, "unpack", str(out / "boot_adb_usb.img"))
    need(cpio((verify / "ramdisk.cpio").read_bytes()) == changed, "Ramdisk repack mismatch")
    need(all(sha((verify / n).read_bytes()) == digest for n, digest in original_parts.items()),
         "Kernel/DTB repack mismatch")
    report = {"schema": 1, "stock_sha256": sha(stock), "candidate_sha256": sha(candidate),
              "candidate_bytes": len(candidate), "magisk_apk_sha256": MAGISK_SHA256,
              "helper_sha256": sha(helper), "public_key_sha256": sha(key),
              "kernel_dtb_preserved": True, "stock_members_except_init_preserved": True,
              "usb_only_rsa_authentication": True, "device_accessed": False,
              "device_installation_verified": False}
    (out / "manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--boot", required=True, type=Path)
    parser.add_argument("--expect-stock-sha256", required=True)
    parser.add_argument("--magisk-apk", required=True, type=Path)
    parser.add_argument("--public-key", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    build(parser.parse_args())


if __name__ == "__main__":
    main()
