"""Build the companion from source using API29 and a new local signing key."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import secrets
import struct
import subprocess
import zipfile
import zlib
from toolchain import ROOT, resolve, sha

def run(args):
    result = subprocess.run([str(value) for value in args], capture_output=True, timeout=180)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', 'replace')[-5000:])
    return result.stdout.decode('utf-8', 'replace')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--public-release', action='store_true',
                        help='Exclude extracted OEM fonts; read them from the target launcher at runtime.')
    args = parser.parse_args()
    tools = resolve()
    app = ROOT / 'app'
    output = ROOT / 'build/app'
    output.mkdir(parents=True, exist_ok=True)
    classes, dex = output / 'classes', output / 'dex'
    # A new temporary compilation directory prevents stale removed classes.
    import tempfile
    with tempfile.TemporaryDirectory(prefix='compile-', dir=output) as work:
        classes = Path(work) / 'classes'
        dex = Path(work) / 'dex'
        classes.mkdir(); dex.mkdir()
        sources = sorted(p for p in (app / 'src').rglob('*.java')
                         if not {'offline', 'test', 'tests', 'fixtures'}.intersection(p.relative_to(app / 'src').parts))
        run([tools['java'], '-jar', tools['ecj'], '-1.8', '-encoding', 'UTF-8', '-bootclasspath',
             str(tools['android']) + os.pathsep + str(tools['stubs']), '-d', classes, *sources])
        class_jar = Path(work) / 'compiled.jar'
        with zipfile.ZipFile(class_jar, 'w') as archive:
            for compiled in sorted(classes.rglob('*.class')):
                archive.write(compiled, compiled.relative_to(classes).as_posix())
        run([tools['java'], '-cp', tools['d8'], 'com.android.tools.r8.D8', '--min-api', '29',
             '--lib', tools['android'], '--output', dex, class_jar])
        data = bytearray((dex / 'classes.dex').read_bytes())
        if data[:4] != b'dex\n' or struct.unpack_from('<I', data, 32)[0] != len(data):
            raise RuntimeError('Invalid generated DEX')
        # Work around this pinned legacy D8 build's signature length bug.
        data[12:32] = hashlib.sha1(data[32:]).digest()
        struct.pack_into('<I', data, 8, zlib.adler32(data[12:]) & 0xffffffff)
        manifest = output / 'manifest.apk'
        run([tools['aapt2'], 'link', '-o', manifest, '--manifest', app / 'AndroidManifest.xml', '-I', tools['android']])
        unsigned = output / 'unsigned.apk'
        with zipfile.ZipFile(manifest) as source, zipfile.ZipFile(unsigned, 'w') as target:
            for info in source.infolist():
                target.writestr(info, source.read(info.filename))
            target.writestr('classes.dex', data)
            for asset in sorted((app / 'assets').rglob('*')):
                if asset.is_file() and not (args.public_release and 'fonts' in asset.relative_to(app / 'assets').parts):
                    target.write(asset, 'assets/' + asset.relative_to(app / 'assets').as_posix())
            fonts = ROOT / '.local/oem-fonts'
            packaged_fonts = [] if args.public_release else sorted(fonts.glob('*'))
            for font in packaged_fonts:
                if font.suffix in ('.ttf', '.otf'):
                    target.write(font, 'assets/fonts/' + font.name)
            license_file = app / 'src/org/x04g/home/qr/LICENSE-MIT.txt'
            if (app / 'src/org/x04g/home/qr/QrCode.java').exists():
                target.write(license_file, 'assets/notices/nayuki-qr.txt')
            target.write(ROOT / 'LICENSE.md', 'assets/notices/project-MIT.txt')
        signing = ROOT / '.local/signing'
        signing.mkdir(parents=True, exist_ok=True)
        password, keystore = signing / 'password.txt', signing / 'app.p12'
        if not password.exists():
            password.write_text(secrets.token_hex(24), encoding='ascii')
            try: password.chmod(0o600)
            except OSError: pass
        if not keystore.exists():
            run([tools['keytool'], '-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12',
                 '-storepass:file', password, '-keypass:file', password, '-alias', 'x04g', '-keyalg',
                 'RSA', '-keysize', '3072', '-validity', '7300', '-dname', 'CN=X04G Camera Monitor', '-noprompt'])
        aligned, signed = output / 'aligned.apk', output / 'x04g-home.apk'
        run([tools['zipalign'], '-f', '4', unsigned, aligned])
        run([tools['java'], '-jar', tools['apksigner'], 'sign', '--ks', keystore, '--ks-key-alias',
             'x04g', '--ks-pass', 'file:' + str(password), '--out', signed, aligned])
        verification = run([tools['java'], '-jar', tools['apksigner'], 'verify', '--verbose', '--print-certs', signed])
        badging = run([tools['aapt2'], 'dump', 'badging', signed])
        if "package: name='org.x04g.home'" not in badging or 'launchable-activity:' in badging:
            raise RuntimeError('Companion package/launcher contract violated')
        report = dict(schema=1, apk='build/app/x04g-home.apk', apk_sha256=sha(signed),
                      dex_sha256=hashlib.sha256(data).hexdigest(), sources={p.relative_to(ROOT).as_posix(): sha(p) for p in sources},
                      signature_verified=True, device_accessed=False,
                      public_release=args.public_release,
                      fonts_included=sorted(p.name for p in packaged_fonts if p.suffix in ('.ttf', '.otf')))
        (output / 'signature.txt').write_text(verification, encoding='utf-8')
        (output / 'build-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({k: report[k] for k in ('apk', 'apk_sha256', 'signature_verified', 'device_accessed')}))

if __name__ == '__main__':
    main()
