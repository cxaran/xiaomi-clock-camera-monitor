#!/usr/bin/env python3
"""Compose the OEM laboratory trees from read-only, user-supplied filesystems.

No Android device, network endpoint or existing output is selected or modified.
The recipe contains hashes, minimal XOR deltas and virtual-hardware AOSP code;
the original APKs/framework are read from the owner's backup.
"""
from pathlib import Path, PurePosixPath
import argparse
import hashlib
import json
import os
import stat
import zlib

HERE = Path(__file__).resolve().parent
RECIPE = HERE / "recipe/composition.json"
MAX_DOCUMENT = 4 * 1024 * 1024
MAX_PAYLOAD = 16 * 1024 * 1024


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def relative(value):
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or "\x00" in value or "\\" in value:
        raise ValueError("Non-canonical relative recipe path")
    return path


def inside(root, value):
    parts = relative(value).parts
    current = root
    for part in parts:
        if current.is_symlink():
            raise ValueError("A path ancestor is a symlink")
        current /= part
    return current


def load_recipe():
    if RECIPE.stat().st_size > MAX_DOCUMENT:
        raise ValueError("Recipe exceeds limit")
    recipe = json.loads(RECIPE.read_text(encoding="utf-8"))
    if recipe["schema"] != "x04g.oem-emulator-composition.v1":
        raise ValueError("Unknown recipe schema")
    seen = set()
    for row in recipe["nodes"]:
        if row["image"] not in ("system", "vendor") or not row["path"].startswith("/"):
            raise ValueError("Unexpected destination")
        relative(row["path"].lstrip("/"))
        key = row["image"], row["path"]
        if key in seen:
            raise ValueError("Duplicate node")
        seen.add(key)
        expected = {"file": stat.S_IFREG, "directory": stat.S_IFDIR,
                    "symlink": stat.S_IFLNK}.get(row["kind"])
        if stat.S_IFMT(row["mode"]) != expected:
            raise ValueError("Recipe node type mismatch")
        if "source" in row:
            if row["source"]["group"] not in recipe["inputs"]:
                raise ValueError("Unknown source group")
            relative(row["source"]["path"])
        for key in ("payload", "delta"):
            if key in row and (Path(row[key]).name != row[key] or row[key] not in recipe["payloads"]):
                raise ValueError("Unexpected payload name")
    return recipe


def unpack(recipe, name):
    path = HERE / "recipe/payloads" / name
    item = recipe["payloads"][name]
    if path.is_symlink() or not path.is_file() or digest(path) != item["sha256"]:
        raise ValueError("Payload hash mismatch")
    limit = item["expanded_bytes"]
    if not 0 <= limit <= MAX_PAYLOAD:
        raise ValueError("Payload expansion exceeds limit")
    decoder = zlib.decompressobj()
    value = decoder.decompress(path.read_bytes(), limit + 1)
    if len(value) != limit or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("Invalid compressed payload")
    return value


def source_bytes(row, roots):
    item = row["source"]
    path = inside(roots[item["group"]], item["path"])
    if path.is_symlink() or not path.is_file() or path.stat().st_size != item["bytes"]:
        raise ValueError("Source file type/size mismatch: " + item["group"] + "/" + item["path"])
    if digest(path) != item["sha256"]:
        raise ValueError("Source hash mismatch: " + item["group"] + "/" + item["path"])
    return path.read_bytes()


def file_bytes(recipe, row, roots):
    if "source" in row:
        data = source_bytes(row, roots)
        if "delta" in row:
            delta = unpack(recipe, row["delta"])
            data = bytes(value ^ (data[index] if index < len(data) else 0)
                         for index, value in enumerate(delta))
    else:
        data = unpack(recipe, row["payload"])
    if len(data) != row["bytes"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
        raise ValueError("Composed content mismatch: " + row["path"])
    return data


def metadata(path, row):
    os.chown(path, row["uid"], row["gid"], follow_symlinks=False)
    if row["kind"] != "symlink":
        os.chmod(path, stat.S_IMODE(row["mode"]))
    for name, value in row["xattrs"].items():
        os.setxattr(path, name, bytes.fromhex(value), follow_symlinks=False)


def compose(recipe, roots, output):
    if os.name != "posix" or os.geteuid() != 0:
        raise RuntimeError("Linux/WSL root is required for numeric owners and SELinux xattrs")
    output = output.absolute()
    if output.exists() or output.is_symlink() or not output.parent.is_dir():
        raise ValueError("Output must be a new directory with an existing parent")
    if output.parent.resolve() != output.parent:
        raise ValueError("Output parent must not contain symlinks")
    # Validate every source and delta before creating output. Reads are repeated
    # during copying so that a changed source cannot silently bypass its hash.
    for row in recipe["nodes"]:
        if row["kind"] == "file":
            file_bytes(recipe, row, roots)
    output.mkdir(mode=0o700)
    trees = {name: output / name for name in ("system", "vendor")}
    for path in trees.values():
        path.mkdir()
    for row in sorted(recipe["nodes"], key=lambda row: (len(PurePosixPath(row["path"]).parts), row["path"])):
        path = inside(trees[row["image"]], row["path"].lstrip("/"))
        if row["path"] == "/":
            continue
        if row["kind"] == "directory":
            path.mkdir()
        elif row["kind"] == "symlink":
            path.symlink_to(row["target"])
        else:
            with path.open("xb") as stream:
                stream.write(file_bytes(recipe, row, roots))
    # Directory chmod last: some original directories are deliberately 0555.
    for row in reversed(recipe["nodes"]):
        metadata(inside(trees[row["image"]], row["path"].lstrip("/")), row)
    result = {"schema": "x04g.composed-trees.v1", "nodes": len(recipe["nodes"]),
              "recipe_sha256": digest(RECIPE), "personal_userdata_included": False,
              "physical_device_accessed": False}
    (output / "composition-result.json").write_text(json.dumps(result, indent=2) + "\n")
    return trees


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, required=True,
                        help="JSON object with absolute filesystem roots, one per recipe input group")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    recipe = load_recipe()
    values = json.loads(args.inputs.read_text(encoding="utf-8"))
    roots = {name: Path(values[name]).resolve(strict=True) for name in recipe["inputs"]}
    if not all(path.is_dir() for path in roots.values()):
        raise ValueError("Every source root must be a filesystem directory")
    if args.verify_only:
        for row in recipe["nodes"]:
            if row["kind"] == "file":
                file_bytes(recipe, row, roots)
        print(json.dumps({"verified_nodes": len(recipe["nodes"]), "output_written": False}))
    else:
        compose(recipe, roots, args.output)
        print(json.dumps({"output": str(args.output), "nodes": len(recipe["nodes"])}))


if __name__ == "__main__":
    main()
