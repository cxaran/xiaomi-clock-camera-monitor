#!/usr/bin/env python3
"""Download the exact public ARM64 API29 SDK and its compatible emulator engine."""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import tarfile
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def archive(row, path):
    if path.exists():
        if path.is_symlink() or path.stat().st_size != row["bytes"] or digest(path) != row["sha256"]:
            raise ValueError("An existing cache file fails its pinned hash; preserve it and use a new cache")
        return
    temporary = path.with_suffix(path.suffix + ".part")
    if temporary.exists():
        raise ValueError("An incomplete download already exists; inspect it before retrying")
    with urllib.request.urlopen(row["url"], timeout=60) as response, temporary.open("xb") as stream:
        size = 0
        while block := response.read(1024 * 1024):
            size += len(block)
            if size > row["bytes"]:
                raise ValueError("Download exceeds its pinned size")
            stream.write(block)
    if temporary.stat().st_size != row["bytes"] or digest(temporary) != row["sha256"]:
        raise ValueError("Download hash/size mismatch")
    temporary.rename(path)


def extract_zip(source, destination):
    with zipfile.ZipFile(source) as package:
        for item in package.infolist():
            path = PurePosixPath(item.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in item.filename:
                raise ValueError("Archive path escapes destination")
            mode = item.external_attr >> 16
            if mode & 0o170000 == 0o120000:
                raise ValueError("Unexpected symlink in SDK ZIP")
        package.extractall(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="NEW directory on Linux/WSL")
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.output.is_symlink():
        raise ValueError("Output must be new")
    args.cache.mkdir(parents=True, exist_ok=True)
    sdk = json.loads((HERE / "recipe/sdk-pins.json").read_text())
    engine = json.loads((HERE / "recipe/engine-pins.json").read_text())
    zip_path = args.cache / "arm64-v8a-29_r08.zip"
    tar_path = args.cache / "emulator29-linux.tar.gz"
    archive(sdk["sdk_archive"], zip_path)
    archive(engine["archive"], tar_path)
    args.output.mkdir(parents=True)
    extract_zip(zip_path, args.output / "sdk")
    engine_dir = args.output / "engine"
    engine_dir.mkdir()
    with tarfile.open(tar_path, "r:gz") as package:
        # Python's data filter checks names, links and special files.
        package.extractall(engine_dir, filter="data")
    sysdir = args.output / "sdk/arm64-v8a"
    for root, pins in ((sysdir, sdk["files"]), (engine_dir, engine["files"])):
        for row in pins:
            path = root / row["name"]
            if path.is_symlink() or path.stat().st_size != row["bytes"] or digest(path) != row["sha256"]:
                raise ValueError("Extracted file fails its pinned hash: " + row["name"])
    print(json.dumps({"sdk_sysdir": str(sysdir), "engine": str(engine_dir), "verified": True}))


if __name__ == "__main__":
    main()
