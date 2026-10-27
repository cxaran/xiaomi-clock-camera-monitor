"""Check the distributable tree, not local caches, keys or firmware."""
from pathlib import Path
import argparse
import json
import os
import re
import subprocess
import zlib
from toolchain import ROOT

SKIP = {'.git', '.cache', '.local', '.venv', '__pycache__', 'build', 'state', 'images', 'work', 'backups'}
FORBIDDEN_SUFFIX = {'.p12', '.jks', '.keystore', '.dpapi', '.pcap', '.pcapng', '.img', '.apk'}

def files():
    result = []
    for directory, children, names in os.walk(ROOT, topdown=True, followlinks=False):
        # Prune before stat/read: private WSL build trees can contain inaccessible
        # Linux symlinks, and private data should never be inspected by this audit.
        children[:] = [name for name in children if name not in SKIP]
        for name in names:
            path = Path(directory) / name
            if path.is_symlink():
                raise ValueError('Unexpected symlink in distributable tree: ' + str(path.relative_to(ROOT)))
            if path.is_file():
                result.append(path)
    return sorted(result)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, default=ROOT / 'build/privacy-audit.json')
    args = parser.parse_args()
    issues = []
    patterns = {
        'private_key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\r\n]+[A-Za-z0-9+/]{32,}'),
        'personal_windows_path': re.compile(r'[A-Za-z]:[\\/]Users[\\/][^\\/\s]+', re.I),
        'personal_wsl_path': re.compile(r'/mnt/[a-z]/Users/[^/\s]+', re.I),
    }
    inspected = files()
    for path in inspected:
        relative = path.relative_to(ROOT).as_posix()
        if path.suffix.lower() in FORBIDDEN_SUFFIX or path.name in ('adbkey', 'adbkey.pub'):
            issues.append({'file': relative, 'rule': 'private_or_firmware_file'})
        if path.suffix.lower() in {'.png', '.jpg'}:
            continue
        raw = path.read_bytes()
        if path.suffix.lower() in {'.zlib', '.gz'}:
            decoder = zlib.decompressobj(16 + zlib.MAX_WBITS if path.suffix.lower() == '.gz' else zlib.MAX_WBITS)
            raw = decoder.decompress(raw, 32 * 1024 * 1024 + 1)
            if len(raw) > 32 * 1024 * 1024 or decoder.unconsumed_tail or not decoder.eof:
                issues.append({'file': relative, 'rule': 'compressed_input_not_fully_inspected'})
                continue
        # ASCII paths/PEM text remain detectable in ELF and expanded kernel data.
        data = raw.decode('utf-8', 'replace')
        for name, pattern in patterns.items():
            for match in pattern.finditer(data):
                issues.append({'file': relative, 'line': data[:match.start()].count('\n') + 1, 'rule': name})
    result = dict(schema=1, passed=not issues, files_inspected=len(inspected), issues=issues,
                  scope='Distributable files only; screenshots require visual review; no assurance about arbitrary future edits.')
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))
    raise SystemExit(0 if result['passed'] else 1)

if __name__ == '__main__': main()

