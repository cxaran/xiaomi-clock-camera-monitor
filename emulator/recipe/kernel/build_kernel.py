#!/usr/bin/env python3
"""Rebuild the emulator's AOSP Goldfish kernel, never a physical clock BOOT.

Pinned public archives, exact source-tree identities, complete patch and config.
Requires Linux x86_64. Output and downloads belong in a local Linux filesystem.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import stat
import subprocess
import tarfile
import urllib.request

HERE = Path(__file__).resolve().parent


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def need(ok, message):
    if not ok:
        raise ValueError(message)


def run(command, *, cwd=None, env=None, capture=False):
    process = subprocess.run([str(x) for x in command], cwd=cwd, env=env,
                             stdout=subprocess.PIPE if capture else None,
                             stderr=subprocess.PIPE if capture else None, check=False)
    need(process.returncode == 0, "Command failed: " + str(command[0]))
    return process.stdout.decode("utf-8").strip() if capture else ""


def safe_extract(archive, destination):
    """Python data filter rejects escaping links, traversal and special files."""
    destination.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive, "r:gz") as bundle:
        # Check every member before extracting any data. data_filter also
        # resolves link destinations, strips ownership and unsafe permission bits.
        for member in bundle:
            tarfile.data_filter(member, str(destination))
        def canonical(member, target):
            filtered = tarfile.data_filter(member, target)
            # Python 3.14 normalizes symbolic-link spellings. Git tree identity
            # includes the original target bytes, including harmless ./ parts.
            if member.issym():
                filtered.linkname = member.linkname
            if member.isfile():
                filtered.mode = 0o755 if member.mode & 0o111 else 0o644
            return filtered
        bundle.extractall(destination, filter=canonical)


def source_tree(source):
    # Hash original blob bytes, without Git ignore rules, text filters, global
    # user configuration or index metadata altering the exported source tree.
    records = []
    for item in source.iterdir():
        if item.is_symlink():
            mode, raw = b"120000", os.fsencode(os.readlink(item))
        elif item.is_dir():
            records.append((os.fsencode(item.name) + b"/", b"40000", item.name,
                            bytes.fromhex(source_tree(item))))
            continue
        else:
            mode = b"100755" if stat.S_IMODE(item.stat().st_mode) & 0o111 else b"100644"
            raw = item.read_bytes()
        oid = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).digest()
        records.append((os.fsencode(item.name), mode, item.name, oid))
    payload = b"".join(mode + b" " + os.fsencode(name) + b"\0" + oid
                       for _, mode, name, oid in sorted(records))
    return hashlib.sha1(b"tree " + str(len(payload)).encode() + b"\0" + payload).hexdigest()


def build(args):
    need(os.name == "posix" and platform.machine().lower() in ("x86_64", "amd64"),
         "Linux x86_64 is required")
    lock = json.loads((HERE / "sources.lock.json").read_text())
    need(sha(HERE / "complete.patch") == lock["patch_sha256"], "Patch checksum mismatch")
    need(sha(HERE / "config") == lock["config_sha256"], "Config checksum mismatch")
    work = args.work_dir.resolve()
    need(not work.exists(), "Choose a fresh work directory; no automatic retries or overwrites")
    cache = args.cache.resolve()
    cache.mkdir(parents=True, exist_ok=True)
    archives = {}
    for entry in lock["archives"]:
        path = cache / (entry["name"] + ".tar.gz")
        if not path.is_file() or sha(path) != entry["sha256"]:
            need(not args.offline, "Missing verified archive: " + path.name)
            temporary = path.with_suffix(".part")
            with urllib.request.urlopen(entry["url"], timeout=60) as source, temporary.open("wb") as out:
                shutil.copyfileobj(source, out, 1024 * 1024)
            need(temporary.stat().st_size == entry["bytes"] and sha(temporary) == entry["sha256"],
                 "Downloaded archive does not match lock: " + path.name)
            temporary.replace(path)
        need(path.stat().st_size == entry["bytes"], "Archive size mismatch")
        archives[entry["name"]] = path
    work.mkdir()
    source = work / "source"
    clang = work / "clang"
    binutils = work / "binutils"
    safe_extract(archives["kernel"], source)
    safe_extract(archives["clang_r365631c"], clang)
    safe_extract(archives["binutils_arm64"], binutils)
    need(source_tree(source) == lock["base_tree"], "AOSP kernel base-tree mismatch")
    need(source_tree(clang) == lock["archives"][1]["expected_git_tree"], "Clang tree mismatch")
    need(source_tree(binutils) == lock["archives"][2]["expected_git_tree"], "Binutils tree mismatch")
    run(["git", "-C", source, "apply", "--check", HERE / "complete.patch"])
    run(["git", "-C", source, "apply", HERE / "complete.patch"])
    need(source_tree(source) == lock["patched_tree"], "Patched kernel source-tree mismatch")
    out = work / "out"
    out.mkdir()
    shutil.copyfile(HERE / "config", out / ".config")
    env = dict(os.environ)
    for name in ("LD_LIBRARY_PATH", "LD_PRELOAD", "GCC_EXEC_PREFIX", "COMPILER_PATH", "CPATH",
                 "C_INCLUDE_PATH", "CPLUS_INCLUDE_PATH", "LIBRARY_PATH", "MAKEFLAGS", "MFLAGS",
                 "KCONFIG_CONFIG", "KCONFIG_ALLCONFIG", "KBUILD_OUTPUT", "KBUILD_SRC", "KBUILD_EXTMOD",
                 "KCFLAGS", "HOSTCFLAGS", "HOSTCXXFLAGS", "LLVM", "LLVM_IAS"):
        env.pop(name, None)
    env.update(lock["build"])
    env["PATH"] = str(clang / "bin") + ":" + str(binutils / "bin") + ":/usr/bin:/bin"
    env["LC_ALL"] = "C"
    command = [args.make, "-C", source, "O=" + str(out), "ARCH=arm64",
               "CC=" + str(clang / "bin/clang.real"), "CLANG_TRIPLE=aarch64-linux-gnu-",
               "CROSS_COMPILE=" + str(binutils / "bin/aarch64-linux-androidkernel-"),
               "HOSTCC=" + args.hostcc, "LOCALVERSION=" + lock["build"]["LOCALVERSION"]]
    report = {"source_tree": lock["patched_tree"], "target_compiler": "clang-r365631c/clang.real",
              "config_sha256": sha(out / ".config"), "device_accessed": False,
              "prepared": True, "compiled": False}
    if not args.prepare_only:
        run(command + ["olddefconfig"], env=env)
        need(sha(out / ".config") == lock["config_sha256"], "olddefconfig changed the pinned config")
        run(command + ["-j" + str(args.jobs), "Image.gz", "modules"], env=env)
        image = out / "arch/arm64/boot/Image.gz"
        report.update(compiled=True, image_sha256=sha(image), image_bytes=image.stat().st_size)
    (work / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache/x04g-kernel-archives")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--hostcc", default="gcc")
    parser.add_argument("--make", default="make")
    parser.add_argument("--jobs", type=int, choices=range(1, 33), default=2)
    build(parser.parse_args())


if __name__ == "__main__":
    main()
