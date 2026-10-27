#!/usr/bin/env python3
"""Optional cross-build of the distributed virtual hardware sources, on Linux.

OEM/SDK link providers come from the caller's original read-only filesystem
roots. No provider binary, account or credential is bundled with the sources.
This command never installs its output or updates the pinned image recipe.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import sys
import tarfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import compose


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, required=True, help="Filesystem roots JSON, as used by compose.py")
    parser.add_argument("--output", type=Path, required=True, help="NEW local build directory")
    parser.add_argument("--ndk", type=Path, required=True, help="Extracted android-ndk-r20b")
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--ninja", default="ninja")
    args = parser.parse_args()
    if os.name != "posix":
        raise RuntimeError("Use Linux/WSL")
    lock = json.loads((HERE / "sources.lock.json").read_text())
    package = HERE / lock["archive"]["name"]
    if compose.digest(package) != lock["archive"]["sha256"]:
        raise ValueError("Source archive hash mismatch")
    props = dict(line.strip().split("=", 1) for line in (args.ndk / "source.properties").read_text().splitlines() if "=" in line)
    props = {key.strip(): value.strip() for key, value in props.items()}
    if props.get("Pkg.Revision") != lock["ndk"]["version"]:
        raise ValueError("The frozen native build requires NDK r20b")
    roots_doc = json.loads(args.inputs.read_text())
    roots = {key: Path(value).resolve(strict=True) for key, value in roots_doc.items()}
    providers = json.loads((HERE / "providers.json").read_text())["files"]
    for row in providers:
        # Reuse the exact source file verifier, before producing any output.
        compose.source_bytes(row, roots)
    output = args.output.resolve()
    if any(character.isspace() for character in str(output)):
        raise ValueError("Choose a canonical Linux build path without whitespace")
    if output.exists() or output.is_symlink() or not output.parent.is_dir():
        raise ValueError("Output must be new")
    output.mkdir(mode=0o700)
    reference = output / "reference"
    reference.mkdir()
    with tarfile.open(package, "r:gz") as archive:
        archive.extractall(reference, filter="data")
    for row in lock["files"]:
        path = compose.inside(reference, row["path"])
        if path.is_symlink() or not path.is_file() or path.stat().st_size != row["bytes"] or compose.digest(path) != row["sha256"]:
            raise ValueError("Extracted source mismatch")
    for row in providers:
        path = compose.inside(reference, row["destination"])
        path.parent.mkdir(parents=True, exist_ok=True)
        data = compose.source_bytes(row, roots)
        if path.exists():
            if path.read_bytes() != data:
                raise ValueError("Conflicting native link provider")
        else:
            with path.open("xb") as stream:
                stream.write(data)
    alias = reference / ".private/wifi_hal_mode3_build/prebuilt02/bin/ld-android.so"
    if not alias.exists():
        alias.symlink_to("linker64")
    builds = [
        ("graphics", "emulacion_x04g/graficos32/goldfish2020_readfix", ".private/graficos2020_readfix_build/build", "armeabi-v7a", ["GLESv1_enc", "GLESv2_enc", "OpenglCodecCommon", "OpenglSystemCommon", "renderControl_enc", "gralloc_ranchu"]),
        ("window", "emulacion_x04g/graficos32/goldfish2020_window", "build-window", "armeabi-v7a", ["GLESv1_CM_emulation", "GLESv2_emulation", "EGL_emulation"]),
        ("mapper", "emulacion_x04g/graficos32/hidl32/mapper2021_sphal", "build-mapper", "armeabi-v7a", ["mapper2021_image"]),
        ("bluetooth", ".private/bluetooth_shared15_clock_build01/virtio_v5", "build-bluetooth", "armeabi-v7a", ["shared15_clock_hci_virtio"]),
        ("wifi", "emulacion_x04g/wifi_hal_mode3", "build-wifi", "arm64-v8a", ["android.hardware.wifi_mode3_service"]),
    ]
    records = []
    for name, source, build, abi, targets in builds:
        command = [args.cmake, "-S", str(reference / source), "-B", str(reference / build), "-G", "Ninja",
                   "-DCMAKE_TOOLCHAIN_FILE=" + str(args.ndk.resolve() / "build/cmake/android.toolchain.cmake"),
                   "-DCMAKE_MAKE_PROGRAM=" + args.ninja, "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-29",
                   "-DANDROID_STL=none", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_CXX_STANDARD_LIBRARIES=",
                   "-DX04G_ROOT=" + str(reference), "-DX04G_WORKSPACE=" + str(reference),
                   "-DCMAKE_C_FLAGS=-fdebug-prefix-map=" + str(reference) + "=/build/x04g",
                   "-DCMAKE_CXX_FLAGS=-fdebug-prefix-map=" + str(reference) + "=/build/x04g"]
        for phase, values in (("configure", command), ("build", [args.cmake, "--build", str(reference / build), "--parallel", "2", "--target", *targets])):
            result = subprocess.run(values, capture_output=True, text=True)
            (output / (name + "-" + phase + ".stdout.log")).write_text(result.stdout)
            (output / (name + "-" + phase + ".stderr.log")).write_text(result.stderr)
            records.append({"component": name, "phase": phase, "returncode": result.returncode})
            (output / "build-result.json").write_text(json.dumps({"steps": records, "installed": False}, indent=2) + "\n")
            if result.returncode:
                raise RuntimeError("Native " + name + "/" + phase + " failed; inspect its local logs")
    # NDK r20b's Clang8 supports fdebug-prefix-map, but not ffile-prefix-map.
    # Diagnostic __FILE__ literals therefore get the same fixed-length
    # redaction used for the distributed virtual-hardware ELF payloads.
    redacted = []
    for name, source, build, abi, targets in builds:
        directory = reference / build
        candidates = list((directory / "lib").glob("*.so")) + list((directory / "bin").glob("*"))
        for path in candidates:
            if path.is_symlink() or not path.is_file():
                continue
            data = path.read_bytes()
            if not data.startswith(bytes.fromhex("7f454c46")):
                continue
            original = hashlib.sha256(data).hexdigest()
            for value, replacement in ((reference, b"/build/x04g"), (args.ndk.resolve(), b"/n")):
                prefix = str(value).encode()
                if len(prefix) < len(replacement):
                    raise ValueError("Build prefix is too short for its privacy mapping")
                data = data.replace(prefix, replacement.ljust(len(prefix), b"_"))
            if hashlib.sha256(data).hexdigest() != original:
                path.write_bytes(data)
            redacted.append({"component": name, "path": path.relative_to(output).as_posix(),
                             "sha256_before": original, "sha256": hashlib.sha256(data).hexdigest()})
    (output / "native-outputs.json").write_text(json.dumps({"files": redacted,
        "fixed_length_diagnostic_path_redaction": True, "OEM_providers_modified": False}, indent=2) + "\n")
    print(json.dumps({"native_build_pass": True, "output": str(output), "installed": False,
                      "pinned_image_recipe_modified": False}))


if __name__ == "__main__":
    main()
