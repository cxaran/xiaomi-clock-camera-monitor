"""Fetch public ADB/BOOT tools. Does not access a USB device or install drivers."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request
from fetch_tools import unpack
from toolchain import ROOT, CACHE, sha

MAGISK = 'ae1a02b1ab608a51d5bc9b323e0588d06d30d9987ac8da01f4710d76f705dccb'
PLATFORM = '45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99'
MTK = '7cb40e727727dfe3faa065eefb29b16ed29bbdf1'
MEDIATEK_ADB = 'bcebf41bfc2c081544cc3ce4ebdc0b8d890f6411c1d2b925dc2f6b6a0ccd6c5b'

def get(url, path, expected, offline):
    if path.is_file() and sha(path) == expected:
        return
    if offline:
        raise SystemExit('Falta la descarga verificada: ' + path.name)
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_suffix(path.suffix + '.part')
    with urllib.request.urlopen(url, timeout=60) as source, part.open('wb') as output:
        shutil.copyfileobj(source, output, 1024 * 1024)
    if sha(part) != expected:
        part.unlink()
        raise SystemExit('Checksum incorrecto: ' + path.name + '. No se aceptan versiones nuevas implícitamente; revisa versión y hash upstream.')
    part.replace(path)

def run(args):
    result = subprocess.run([str(value) for value in args], capture_output=True, timeout=180)
    if result.returncode:
        raise SystemExit(result.stderr.decode('utf-8', 'replace')[-1500:])
    return result.stdout.decode('utf-8', 'replace').strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mtkclient', action='store_true', help='Clone the pinned source; does not install Python dependencies')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--windows-adb-driver', action='store_true', help='Fetch the signed MediaTek WinUSB CAB only; never installs it')
    args = parser.parse_args()
    magisk = CACHE / 'magisk/Magisk-v26.1.apk'
    get('https://github.com/topjohnwu/Magisk/releases/download/v26.1/Magisk-v26.1.apk', magisk, MAGISK, args.offline)
    archive = ROOT / '.cache/downloads/platform-tools-37.0.1-windows.zip'
    get('https://dl.google.com/android/repository/platform-tools-latest-windows.zip', archive, PLATFORM, args.offline)
    unpack(archive, CACHE / 'platform-tools-windows')
    if args.windows_adb_driver:
        get('https://catalog.s.download.windowsupdate.com/c/msdownload/update/driver/drvs/2016/07/20896854_0c68721e8170c38c9f8055949befbe082f5ee981.cab',
            CACHE / 'mediatek-adb/mediatek-winusb.cab', MEDIATEK_ADB, args.offline)
    if args.mtkclient:
        target = CACHE / 'mtkclient'
        if not target.exists():
            if args.offline:
                raise SystemExit('Falta el checkout MTKClient; omite --offline para obtenerlo.')
            run(['git', 'clone', '--no-checkout', 'https://github.com/bkerler/mtkclient', target])
            run(['git', '-C', target, 'checkout', '--detach', MTK])
        if run(['git', '-C', target, 'rev-parse', 'HEAD']) != MTK or run(['git', '-C', target, 'status', '--porcelain']):
            raise SystemExit('El checkout MTKClient no corresponde al commit limpio requerido.')
    print(json.dumps(dict(public_tools_verified=True, driver_downloaded=args.windows_adb_driver,
                          driver_installed=False, USB_accessed=False)))

if __name__ == '__main__': main()
