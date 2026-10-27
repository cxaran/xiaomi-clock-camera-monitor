#!/usr/bin/env python3
"""Build a NEW OEM userspace PC image set; never a firmware image for the clock."""
from pathlib import Path
import argparse
import base64
import hashlib
import json
import os
import shutil
import stat

import compose
import image_formats as image

HERE = Path(__file__).resolve().parent
KERNEL_SHA = "0623d8095e8651aa066af8b944de35f974c15991538312989e86769b67d9632c"


def sdk_inputs(directory):
    pins = json.loads((HERE / "recipe/sdk-pins.json").read_text())
    for row in pins["files"]:
        path = directory / row["name"]
        if path.is_symlink() or not path.is_file() or path.stat().st_size != row["bytes"] or compose.digest(path) != row["sha256"]:
            raise ValueError("SDK source mismatch: " + row["name"])
    return pins


def fresh_userdata(path, public_key):
    raw = public_key.read_bytes().split()
    if not raw or len(base64.b64decode(raw[0], validate=True)) != 524:
        raise ValueError("An Android ADB RSA public key (.pub), not a private key, is required")
    # The caller's key label can contain a host/user name; replace it locally.
    key = raw[0] + b" x04g-lab\n"
    path.mkdir(mode=0o771)
    rows = [("", 0o771, 1000, 1000, "system_data_file"),
            ("misc", 0o1771, 1000, 9998, "system_data_file"),
            ("misc/adb", 0o2750, 1000, 2000, "adb_keys_file")]
    for name, mode, uid, gid, label in rows:
        node = path / name
        if name:
            node.mkdir()
        os.chown(node, uid, gid)
        os.chmod(node, mode)
        os.setxattr(node, "security.selinux", ("u:object_r:" + label + ":s0\x00").encode())
    target = path / "misc/adb/adb_keys"
    target.write_bytes(key)
    os.chown(target, 1000, 2000)
    os.chmod(target, 0o640)
    os.setxattr(target, "security.selinux", b"u:object_r:adb_keys_file:s0\x00")


def artifact(path, root):
    return {"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size,
            "sha256": compose.digest(path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, required=True, help="Mounted source root JSON; see docs/emulator.md")
    parser.add_argument("--sdk-sysdir", type=Path, required=True)
    parser.add_argument("--adb-public-key", type=Path, required=True)
    parser.add_argument("--kernel", type=Path, default=HERE / "recipe/kernel/Image.gz")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if os.name != "posix" or os.geteuid() != 0:
        raise RuntimeError("Run in Linux/WSL as root to preserve filesystem metadata")
    output = args.output.absolute()
    if output.exists() or output.is_symlink() or not output.parent.is_dir() or output.parent.resolve() != output.parent:
        raise ValueError("Choose a NEW output directory on a native Linux filesystem")
    if shutil.disk_usage(output.parent).free < 10 * 1024 ** 3:
        raise RuntimeError("At least 10 GiB free is required for intermediate trees/images")
    sdk = args.sdk_sysdir.resolve(strict=True)
    sdk_inputs(sdk)
    if args.kernel.is_symlink() or compose.digest(args.kernel) != KERNEL_SHA:
        raise ValueError("Expected input-enabled Goldfish laboratory kernel")
    public = args.adb_public_key.resolve(strict=True)
    if not public.is_file() or public.stat().st_size > 2048:
        raise ValueError("Bounded public ADB key required")
    recipe = compose.load_recipe()
    roots_doc = json.loads(args.inputs.read_text())
    roots = {name: Path(roots_doc[name]).resolve(strict=True) for name in recipe["inputs"]}
    # Full source/delta validation precedes creation of the output directory.
    for row in recipe["nodes"]:
        if row["kind"] == "file":
            compose.file_bytes(recipe, row, roots)
    output.mkdir(mode=0o700)
    image.EVIDENCE = output / "verification"
    image.EVIDENCE.mkdir()
    trees = compose.compose(recipe, roots, output / "trees")
    raw = output / "raw"
    raw.mkdir()
    sysdir = output / "sysdir"
    sysdir.mkdir()
    sizes = {"system": 2 * 1024 * image.MIB, "vendor": 128 * image.MIB}
    for name, tree in trees.items():
        symlinks = [{"path": row["path"], "mode": oct(row["mode"])} for row in recipe["nodes"]
                    if row["image"] == name and row["kind"] == "symlink"]
        target = raw / (name + ".ext4")
        image.ext4(tree, target, sizes[name], "/" if name == "system" else "vendor", symlinks)
        image.check_tree_image(target, tree, symlinks)
    data_tree = output / "fresh-data"
    fresh_userdata(data_tree, public)
    image.ext4(data_tree, sysdir / "userdata.img", 2 * 1024 * image.MIB, "data", [])
    image.check_tree_image(sysdir / "userdata.img", data_tree, [])
    source = sdk / "system.img"
    geometry, changes = image.lp_metadata(source, sizes["system"], sizes["vendor"])
    vbmeta = image.vbmeta_none()
    with source.open("rb") as stream:
        prefix = stream.read(2 * image.MIB)
        stream.seek(source.stat().st_size - image.MIB)
        suffix = stream.read(image.MIB)
    with (sysdir / "system.img").open("xb") as stream:
        stream.truncate(source.stat().st_size)
        image.write_at(stream, 0, prefix)
        image.write_at(stream, source.stat().st_size - image.MIB, suffix)
        image.write_at(stream, image.MIB, bytes(image.MIB))
        image.write_at(stream, image.MIB, vbmeta)
        image.write_at(stream, 2 * image.MIB, geometry)
        for name in ("system", "vendor"):
            image.copy_at(stream, 2 * image.MIB + changes[name]["offset_within_super"], raw / (name + ".ext4"))
    image.gpt_vendor(sdk / "vendor.img", sysdir / "vendor.img", raw / "vendor.ext4")
    for name in ("advancedFeatures.ini", "build.prop", "source.properties", "encryptionkey.img", "ramdisk.img"):
        shutil.copyfile(sdk / name, sysdir / name)
    shutil.copyfile(args.kernel, sysdir / "kernel-ranchu")
    text = 'major_version: 2\nparam: "androidboot.vbmeta.size=256"\nparam: "androidboot.vbmeta.hash_alg=sha256"\nparam: "androidboot.vbmeta.digest=' + hashlib.sha256(vbmeta).hexdigest() + '"\n'
    (sysdir / "VerifiedBootParams.textproto").write_text(text)
    result = {"schema": "x04g.oem-emulator-images.v1", "profile": recipe["profile"],
              "composition_sha256": compose.digest(compose.RECIPE),
              "files": [artifact(path, output) for path in sorted(sysdir.iterdir()) if path.is_file() and not path.is_symlink()],
              "physical_device_accessed": False, "contains_personal_userdata": False,
              "contains_callers_public_adb_key": True, "avb": "NONE, PC laboratory only"}
    (output / "images.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"images_manifest": str(output / "images.json"), "nodes": len(recipe["nodes"])}))


if __name__ == "__main__":
    main()
