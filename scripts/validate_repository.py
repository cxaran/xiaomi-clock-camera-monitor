"""Validate source snapshot, Python syntax and local documentation links."""
import ast
import json
from pathlib import Path
import re
import urllib.parse
from toolchain import ROOT, sha
from audit_export import files

def main():
    issues = []
    source_manifest = json.loads((ROOT / 'app/sources.sha256.json').read_text('utf-8'))
    for name, expected in source_manifest.items():
        path = ROOT / 'app' / name
        if not path.is_file() or sha(path) != expected:
            issues.append({'file': 'app/' + name, 'rule': 'source_manifest_mismatch'})
    count = dict(python=0, markdown=0, source_pins=len(source_manifest))
    for path in files():
        relative = path.relative_to(ROOT).as_posix()
        if path.suffix == '.py':
            ast.parse(path.read_text('utf-8'), filename=relative)
            count['python'] += 1
        elif path.suffix == '.md':
            count['markdown'] += 1
            text = path.read_text('utf-8')
            for value in re.findall(r'\]\(([^\s)]+)\)', text):
                if value.startswith(('http:', 'https:', 'mailto:', '#')):
                    continue
                value = urllib.parse.unquote(value.split('#', 1)[0])
                if not (path.parent / value).exists():
                    issues.append({'file': relative, 'rule': 'missing_documentation_target', 'target': value})
    report = dict(passed=not issues, checks=count, issues=issues, device_accessed=False)
    output = ROOT / 'build/repository-validation.json'
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))
    raise SystemExit(0 if report['passed'] else 1)

if __name__ == '__main__': main()
