"""Fetch checksum-pinned public tools; never connects to a clock."""
import argparse
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile
from toolchain import ROOT, CACHE, sha, resolve

def unpack(archive, destination):
    destination = destination.resolve()
    with zipfile.ZipFile(archive) as bundle:
        for info in bundle.infolist():
            target = (destination / info.filename).resolve()
            if not target.is_relative_to(destination) or (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Unsafe archive member')
        bundle.extractall(destination)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true', help='Require prefilled .cache/downloads')
    args = parser.parse_args()
    config = json.loads((ROOT / 'tools/toolchain.lock.json').read_text('utf-8'))
    downloads = ROOT / '.cache/downloads'
    downloads.mkdir(parents=True, exist_ok=True)
    for entry in config['archives']:
        path = downloads / entry['name']
        if not path.is_file() or sha(path) != entry['sha256']:
            if args.offline:
                raise SystemExit('Falta el archivo verificado: ' + path.name)
            temporary = path.with_suffix(path.suffix + '.part')
            request = urllib.request.Request(entry['url'], headers={'User-Agent': 'x04g-camera-monitor-tooling'})
            with urllib.request.urlopen(request, timeout=60) as response, temporary.open('wb') as output:
                shutil.copyfileobj(response, output, 1024 * 1024)
            if sha(temporary) != entry['sha256']:
                temporary.unlink()
                raise SystemExit('Checksum incorrecto: ' + entry['name'])
            temporary.replace(path)
        destination = CACHE / entry['destination']
        destination.mkdir(parents=True, exist_ok=True)
        if entry.get('zip', True):
            unpack(path, destination)
        else:
            shutil.copyfile(path, destination / entry['name'])
    resolve()
    print('Toolchain verificado. No se ha accedido a ningún dispositivo.')

if __name__ == '__main__':
    main()

