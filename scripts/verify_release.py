"""Verify a public APK/module pair without contacting Android or the LAN."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
import zipfile
import zlib

from toolchain import ROOT, resolve, sha


def dex_valid(data):
    return (len(data) >= 112 and data[:4] == b'dex\n'
            and struct.unpack_from('<I', data, 32)[0] == len(data)
            and data[12:32] == hashlib.sha1(data[32:]).digest()
            and struct.unpack_from('<I', data, 8)[0] == zlib.adler32(data[12:]) & 0xffffffff)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'build/app/x04g-home.apk')
    parser.add_argument('--module', type=Path, default=ROOT / 'build/integration/x04g-camera-carousel-disabled.zip')
    parser.add_argument('--report', type=Path, default=ROOT / 'build/release-audit.json')
    args = parser.parse_args()
    issues = []

    def require(condition, file, rule):
        if not condition:
            issues.append({'file': file, 'rule': rule})

    patterns = {
        'personal_windows_path': rb'[A-Za-z]:[\\/]Users[\\/][^\\/\s\x00]+',
        'personal_wsl_path': rb'/mnt/[a-z]/Users/[^/\s\x00]+',
        'personal_linux_home': rb'(?<![A-Za-z0-9_])/home/[^/\s\x00]+/',
        'private_key': rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\r\n]+[A-Za-z0-9+/]{32,}',
        'github_token': rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})',
    }
    forbidden = {'.p12', '.jks', '.keystore', '.dpapi', '.pcap', '.pcapng', '.img', '.ttf', '.otf', '.woff', '.woff2'}

    def inspect(name, data):
        for rule, expression in patterns.items():
            require(not re.search(expression, data, re.I), name, rule)

    def entries(path):
        result = {}
        with zipfile.ZipFile(path) as archive:
            require(archive.testzip() is None, path.name, 'zip_crc')
            names = archive.namelist()
            require(len(names) == len(set(names)), path.name, 'duplicate_entries')
            for name in names:
                p = Path(name)
                require(not name.startswith(('/', '\\')) and '..' not in p.parts,
                        path.name + ':' + name, 'unsafe_entry_path')
                require(p.suffix.lower() not in forbidden and p.name not in
                        {'adbkey', 'adbkey.pub', 'uid.pin', 'local-config.json', 'cameras.json'},
                        path.name + ':' + name, 'private_or_oem_file')
                data = archive.read(name)
                inspect(path.name + ':' + name, data)
                result[name] = data
        return result

    apk, module = entries(args.apk), entries(args.module)
    tools = resolve()
    verification = subprocess.run([str(tools['java']), '-jar', str(tools['apksigner']), 'verify',
                                   '--verbose', '--print-certs', str(args.apk)],
                                  capture_output=True, timeout=60)
    text = verification.stdout.decode('utf-8', 'replace')
    require(verification.returncode == 0, args.apk.name, 'apk_signature_invalid')
    signer = re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})', text)
    subject = re.findall(r'Signer #\d+ certificate DN:\s*([^\r\n]+)', text)
    require(len(signer) == 1, args.apk.name, 'one_signer_required')
    require(subject == ['CN=X04G Camera Monitor'], args.apk.name, 'non_project_certificate_identity')
    require(dex_valid(apk.get('classes.dex', b'')), args.apk.name, 'invalid_dex')
    require(dex_valid(module.get('payload.dex', b'')), args.module.name, 'invalid_dex')
    require(module.get('disable') == b'' and module.get('skip_mount') == b'', args.module.name,
            'disabled_no_mount_required')
    native = module.get('zygisk/armeabi-v7a.so', b'')
    require(len(native) >= 52 and native[:6] == b'\x7fELF\x01\x01'
            and struct.unpack_from('<H', native, 18)[0] == 40, args.module.name, 'arm32_elf_required')
    provenance = json.loads(module['provenance.json'])
    sys.path.insert(0, str(ROOT / 'integration'))
    from build_module import apk_identity
    identity = apk_identity(args.apk)
    for key, expected in [('apk_package', identity['package']),
                          ('apk_version_code', identity['version_code']),
                          ('apk_version_name', identity['version_name'])]:
        require(provenance.get(key) == expected, args.module.name, 'pair_' + key)
    for key, expected in [('apk_sha256', sha(args.apk)), ('apk_bytes', args.apk.stat().st_size),
                          ('payload_sha256', hashlib.sha256(module['payload.dex']).hexdigest()),
                          ('native_sha256', hashlib.sha256(native).hexdigest())]:
        require(provenance.get(key) == expected, args.module.name, 'pair_' + key)
    require(bool(signer) and provenance.get('apk_signer_sha256') == signer[0].lower(), args.module.name,
            'pair_signer')
    require(provenance.get('initially_disabled') is True and provenance.get('uid_pin_included') is False,
            args.module.name, 'portable_activation_required')
    require(provenance.get('neutral_build_paths_verified') is True, args.module.name, 'build_path_guard_required')
    for name in ['licenses/project-MIT.txt', 'licenses/magisk-installer-GPL-3.0.txt',
                 'licenses/zygisk-ISC.txt', 'licenses/nayuki-qr-MIT.txt']:
        require(name in module, args.module.name, 'missing_license:' + name)
    for name in ['assets/notices/project-MIT.txt', 'assets/notices/nayuki-qr.txt']:
        require(name in apk, args.apk.name, 'missing_license:' + name)
    allowed_assets = {'assets/adminweb/index.html', 'assets/notices/project-MIT.txt', 'assets/notices/nayuki-qr.txt'}
    require({n for n in apk if n.startswith('assets/')} == allowed_assets, args.apk.name, 'unexpected_asset')
    report = {'schema': 'x04g.public-release-audit.v1', 'passed': not issues,
              'apk_sha256': sha(args.apk), 'module_sha256': sha(args.module),
              'apk_signer_sha256': signer[0].lower() if len(signer) == 1 else None,
              'entries_inspected': len(apk) + len(module), 'issues': issues, 'device_accessed': False,
              'scope': 'Signature, exact APK/module pins, DEX/ELF structure, entries, licenses and privacy patterns; not hardware acceptance.'}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
