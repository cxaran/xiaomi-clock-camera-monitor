"""Pinned Windows APK tools. No Android/device operations."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.cache' / 'toolchain'

def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()

def resolve():
    config = json.loads((ROOT / 'tools' / 'toolchain.lock.json').read_text('utf-8'))
    found = {name: CACHE / entry['path'] for name, entry in config['files'].items()}
    for name, path in found.items():
        if not path.is_file() or sha(path) != config['files'][name]['sha256']:
            raise SystemExit('Herramienta ausente o alterada: ' + name + '. Ejecuta python scripts/fetch_tools.py')
    # The directories accompanying aapt2/zipalign contain required DLLs.
    found['build'] = found['aapt2'].parent
    if os.name == 'posix':
        # JAR tools work natively in WSL; Windows java.exe does not accept Linux paths.
        native = Path(os.environ['JAVA_HOME']) / 'bin/java' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or '/missing/java')
        if not native.is_file():
            raise SystemExit('Instala OpenJDK 17/21 en Linux/WSL o configura JAVA_HOME.')
        version = subprocess.run([str(native), '-version'], capture_output=True, timeout=10)
        if version.returncode or not re.search(r'version "(?:17|21)[.\"]', (version.stdout + version.stderr).decode('utf-8', 'replace')):
            raise SystemExit('Se necesita OpenJDK 17/21 en Linux/WSL.')
        found['java'] = native
        found['keytool'] = native.with_name('keytool')
    return found

