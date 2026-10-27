"""Extract exactly three fonts from the user's matching stock launcher APK, locally."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent
LAUNCHER_SHA256 = "1e5b52f356ef7c078619dc369cccf704060aca484754cef454fa921598ed8c21"
FONTS = {
    "assets/dart_app.assets/GoogleSans-Regular.nohints.ttf": ("GoogleSans-Regular.ttf", 98488),
    "assets/dart_app.assets/GoogleSans-Medium.nohints.ttf": ("GoogleSans-Medium.ttf", 98320),
    "assets/dart_app.assets/googlematerialicons/GoogleMaterialIcons-Regular.otf": ("GoogleMaterialIcons-Regular.otf", 449924),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / ".local/oem-fonts")
    args = parser.parse_args()
    raw = args.launcher_apk.read_bytes()
    if hashlib.sha256(raw).hexdigest() != LAUNCHER_SHA256:
        parser.error("Launcher version does not match this integration; do not bypass the pin.")
    result = {}
    with zipfile.ZipFile(args.launcher_apk) as archive:
        for entry, (name, length) in FONTS.items():
            data = archive.read(entry)
            if len(data) != length:
                raise RuntimeError("font_size_mismatch")
            result[name] = data
    args.output.mkdir(parents=True, exist_ok=True)
    for name, data in result.items():
        (args.output / name).write_bytes(data)
    print(json.dumps({"fonts": {name: {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                                for name, data in result.items()}, "device_modified": False}))


if __name__ == "__main__":
    main()
