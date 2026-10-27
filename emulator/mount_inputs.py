#!/usr/bin/env python3
"""Mount regular source image files read-only for composition, or unmount them."""
from pathlib import Path
import argparse
import json
import os
import subprocess
import sys

import build_images
import compose


def run(values):
    return subprocess.run(list(map(str, values)), capture_output=True, text=True, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("mount", "unmount"))
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--oem-system", type=Path)
    parser.add_argument("--oem-product", type=Path)
    parser.add_argument("--oem-vendor", type=Path)
    parser.add_argument("--sdk-sysdir", type=Path)
    parser.add_argument("--build-output", type=Path, help="Build images inside the SAME mount namespace, then unmount")
    parser.add_argument("--adb-public-key", type=Path)
    parser.add_argument("--kernel", type=Path)
    args = parser.parse_args()
    if os.name != "posix" or os.geteuid() != 0:
        raise RuntimeError("Linux/WSL root is required")
    state = args.state.absolute()
    if args.operation == "unmount":
        journal = json.loads((state / "mounts.json").read_text())
        if journal["schema"] != "x04g.read-only-input-mounts.v1":
            raise ValueError("Unknown mount journal")
        for row in reversed(journal["mounts"]):
            target = state / row["group"]
            if str(target) != row["mountpoint"]:
                raise ValueError("Unexpected mountpoint")
            if os.path.ismount(target):
                if run(["findmnt", "-rn", "-M", target, "-o", "SOURCE"]).stdout.strip() != row["loop"]:
                    raise ValueError("Mount source changed; preserve it for inspection")
                run(["umount", target])
            current = subprocess.run(["losetup", "-j", row["source"]], capture_output=True, text=True, check=True)
            if any(line.startswith(row["loop"] + ":") for line in current.stdout.splitlines()):
                run(["losetup", "-d", row["loop"]])
        journal["unmounted"] = True
        (state / "mounts.json").write_text(json.dumps(journal, indent=2) + "\n")
        print(json.dumps({"unmounted": True}))
        return
    if state.exists() or state.is_symlink() or not state.parent.is_dir():
        raise ValueError("State must be a NEW directory")
    if any(path is None for path in (args.oem_system, args.oem_product, args.oem_vendor, args.sdk_sysdir)):
        raise ValueError("Provide all three logical OEM partitions and the pinned SDK sysdir")
    if args.build_output is not None and args.adb_public_key is None:
        raise ValueError("--build-output requires your --adb-public-key")
    build_images.sdk_inputs(args.sdk_sysdir)
    sources = [("oem_system", args.oem_system, 0), ("oem_product", args.oem_product, 0),
               ("oem_vendor", args.oem_vendor, 0), ("sdk_system", args.sdk_sysdir / "system.img", 3145728),
               ("sdk_vendor", args.sdk_sysdir / "vendor.img", 1048576)]
    for group, path, offset in sources:
        if path.is_symlink() or not path.is_file() or path.stat().st_size <= offset:
            raise ValueError("Only regular, uncompressed source image files are accepted")
    state.mkdir(mode=0o700)
    journal = {"schema": "x04g.read-only-input-mounts.v1", "mounts": [], "unmounted": False}
    try:
        for group, path, offset in sources:
            target = state / group
            target.mkdir()
            source = path.resolve(strict=True)
            source_hash = compose.digest(source)
            loop = run(["losetup", "--find", "--show", "--read-only", "--offset", offset, source]).stdout.strip()
            row = {"group": group, "source": str(source), "offset": offset, "loop": loop,
                   "mountpoint": str(target), "source_sha256": source_hash}
            journal["mounts"].append(row)
            try:
                (state / "mounts.json").write_text(json.dumps(journal, indent=2) + "\n")
            except Exception:
                run(["losetup", "-d", loop])
                raise
            if run(["blockdev", "--getro", loop]).stdout.strip() != "1":
                raise RuntimeError("Loop device is not read-only")
            run(["mount", "-o", "ro,noload,nodev,nosuid,noexec", loop, target])
    except Exception:
        # Keep the journal with every allocated handle so unmount can close
        # exactly these resources. Never issue global loop/mount cleanup.
        raise RuntimeError("Mount preparation stopped; use unmount with this state directory") from None
    values = {row["group"]: row["mountpoint"] for row in journal["mounts"]}
    (state / "inputs.json").write_text(json.dumps(values, indent=2) + "\n")
    print(json.dumps({"inputs": str(state / "inputs.json"), "read_only": True}))
    if args.build_output is not None:
        command = [sys.executable, str(Path(__file__).with_name("build_images.py")),
                   "--inputs", str(state / "inputs.json"), "--sdk-sysdir", str(args.sdk_sysdir),
                   "--adb-public-key", str(args.adb_public_key), "--output", str(args.build_output)]
        if args.kernel is not None:
            command.extend(["--kernel", str(args.kernel)])
        try:
            # A new wsl.exe invocation can get another mount namespace. The
            # builder must be a descendant of THIS process that mounted inputs.
            subprocess.run(command, check=True)
        finally:
            subprocess.run([sys.executable, str(Path(__file__).resolve()), "unmount", "--state", str(state)], check=True)


if __name__ == "__main__":
    main()
