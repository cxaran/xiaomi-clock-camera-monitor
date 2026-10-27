#!/usr/bin/env python3
"""Verify local BOOT and SECCFG readbacks. Never accesses a USB device."""
import argparse
import hashlib
import json
from pathlib import Path
import struct


def verify(boot, boot_readback, seccfg_stock, seccfg_readback):
    if len(boot) != 16 * 1024 * 1024 or boot[:8] != b"ANDROID!":
        raise ValueError("Expected X04G 16 MiB BOOT candidate")
    if boot_readback != boot:
        raise ValueError("BOOT readback does not match candidate")
    if len(seccfg_stock) != 256 * 1024 or len(seccfg_readback) != len(seccfg_stock):
        raise ValueError("Expected complete X04G SECCFG partition")
    expected = (0x4D4D4D4D, 4, 60, 1, 1, 0, 0x45454545)
    if struct.unpack_from("<7I", seccfg_stock) != expected:
        raise ValueError("Expected stock locked SECCFG v4; do not use this checker for other layouts")
    unlocked = (0x4D4D4D4D, 4, 60, 3, 1, 0, 0x45454545)
    if struct.unpack_from("<7I", seccfg_readback) != unlocked:
        raise ValueError("SECCFG does not report unlocked v4 state")
    if seccfg_stock[512:] != seccfg_readback[512:]:
        raise ValueError("SECCFG tail outside first sector changed")
    if any(seccfg_stock[60:512]) or any(seccfg_readback[60:512]):
        raise ValueError("Unexpected SECCFG sector padding")
    return {"boot_readback_matches": True, "seccfg_unlocked_header": True,
            "seccfg_tail_unchanged": True,
            "seccfg_hardware_authentication_verified": False,
            "boot_sha256": hashlib.sha256(boot).hexdigest(),
            "device_accessed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("boot", "boot-readback", "seccfg-stock", "seccfg-readback"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    result = verify(args.boot.read_bytes(), args.boot_readback.read_bytes(),
                    args.seccfg_stock.read_bytes(), args.seccfg_readback.read_bytes())
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
