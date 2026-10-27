"""Build an offline Windows installer ZIP from a verified public APK/module pair."""
from pathlib import Path
import argparse
import hashlib
import json
import stat
import subprocess
import sys
import zipfile

from toolchain import ROOT, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/installer')
    parser.add_argument('--allow-dirty', action='store_true', help='Development package only; not for publication.')
    args = parser.parse_args()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT))
    if dirty and not args.allow_dirty:
        raise SystemExit('Commit the reviewed source before creating a release installer.')
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_release.py')], check=True)
    audit = json.loads((ROOT / 'build/release-audit.json').read_text('utf-8'))
    if not audit['passed']:
        raise SystemExit('Public binary pair verification failed.')
    apk = ROOT / 'build/app/x04g-home.apk'
    module = ROOT / 'build/integration/x04g-camera-carousel-disabled.zip'
    with zipfile.ZipFile(module) as source:
        provenance = json.loads(source.read('provenance.json'))
    version = provenance['apk_version_name']
    if version != provenance['module_version']:
        raise SystemExit('APK and module release versions must agree.')
    lock = json.loads((ROOT / 'tools/installer-dependencies.json').read_text('utf-8'))
    archive = ROOT / '.cache/downloads' / lock['archive']
    if not archive.is_file() or sha(archive) != lock['archive_sha256']:
        raise SystemExit('Pinned ADB archive missing or changed; fetch the locked platform-tools archive.')
    required = {'installer/Install-X04G.ps1', 'installer/X04G.Installer.psm1',
                'installer/README.md', 'installer/launchers/Install-X04G.cmd',
                'installer/launchers/Check-X04G.cmd', 'installer/launchers/Disable-X04G.cmd'}
    for relative in required:
        if not (ROOT / relative).is_file():
            raise SystemExit('Installer source missing: ' + relative)
    payload = {
        'app/x04g-home.apk': apk.read_bytes(),
        'integration/x04g-camera-carousel-disabled.zip': module.read_bytes(),
        'installer/Install-X04G.ps1': (ROOT / 'installer/Install-X04G.ps1').read_bytes(),
        'installer/X04G.Installer.psm1': (ROOT / 'installer/X04G.Installer.psm1').read_bytes(),
        'installer/README.md': (ROOT / 'installer/README.md').read_bytes(),
        'README-INSTALACION.md': (ROOT / 'installer/README.md').read_bytes(),
        'LICENSE-MIT.txt': (ROOT / 'LICENSE.md').read_bytes(),
        'THIRD_PARTY_NOTICES.md': (ROOT / 'THIRD_PARTY_NOTICES.md').read_bytes(),
    }
    for name in ['Install-X04G.cmd', 'Check-X04G.cmd', 'Disable-X04G.cmd']:
        payload[name] = (ROOT / 'installer/launchers' / name).read_bytes()
    with zipfile.ZipFile(archive) as source:
        for name, expected in lock['files'].items():
            data = source.read(name)
            if len(data) != expected['bytes'] or hashlib.sha256(data).hexdigest() != expected['sha256']:
                raise SystemExit('Pinned ADB entry changed: ' + name)
            payload[name] = data
    manifest = {
        'schema': 'x04g.installer.v1', 'version': version,
        'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'developmentSnapshot': dirty,
        'app': {'path': 'app/x04g-home.apk', 'sha256': sha(apk), 'bytes': apk.stat().st_size,
                'package': provenance['apk_package'], 'versionCode': provenance['apk_version_code'],
                'versionName': version, 'signerSha256': provenance['apk_signer_sha256']},
        'module': {'path': 'integration/x04g-camera-carousel-disabled.zip', 'sha256': sha(module),
                   'bytes': module.stat().st_size, 'id': provenance['module_id'],
                   'version': provenance['module_version'], 'versionCode': provenance['module_version_code']},
        'oem': {'package': 'com.google.assistant.launcher', 'process': 'com.google.android.apps.mediashell',
                'activePath': '/product/app/com.google.assistant.launcher/app.apk',
                'sha256': provenance['stock_launcher_sha256'], 'bytes': 33126600,
                'versionCode': 111706, 'versionName': '11.51.6.416171605'},
        'adb': {'path': 'platform-tools/adb.exe', 'version': lock['version'], 'serverPort': 5142,
                **lock['files']['platform-tools/adb.exe'],
                'files': {name: value for name, value in lock['files'].items()
                          if name.endswith(('.exe', '.dll'))}},
    }
    payload['installer-manifest.json'] = (json.dumps(manifest, indent=2) + '\n').encode('utf-8')
    payload['SHA256SUMS.txt'] = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n'
                                      for name, data in sorted(payload.items())).encode('ascii')
    # This allowlist is intentionally independent of os.walk: no keys, private
    # profiles, ADB server state, backups or reports can enter the package.
    for name in payload:
        if any(part in {'.local', '.cache', 'logs', 'backups', '.private'} for part in Path(name).parts):
            raise SystemExit('Unexpected private package path.')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    target = out / ('x04g-windows-installer-' + version + '.zip')
    prefix = 'X04G-Installer-' + version + '/'
    with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name, data in sorted(payload.items()):
            entry = zipfile.ZipInfo(prefix + name, (2026, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (stat.S_IFREG | 0o644) << 16
            output.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    with zipfile.ZipFile(target) as check:
        if check.testzip() is not None or len(check.namelist()) != len(payload):
            raise SystemExit('Installer ZIP verification failed.')
        for name, data in payload.items():
            if check.read(prefix + name) != data:
                raise SystemExit('Installer ZIP bytes differ.')
    report = {'schema': 'x04g.installer-build.v1', 'zip': target.name, 'sha256': sha(target),
              'bytes': target.stat().st_size, 'entries': len(payload), 'version': version,
              'source_commit': manifest['sourceCommit'], 'development_snapshot': dirty,
              'APK_and_module_verified': True, 'ADB_archive_verified': True, 'device_accessed': False}
    (out / 'installer-build-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
