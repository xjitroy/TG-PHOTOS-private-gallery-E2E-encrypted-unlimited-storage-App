#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess
import sys

project = Path(__file__).resolve().parents[1]
# Inspect tracked and new source; ignore developer-local files via .gitignore.
names = subprocess.check_output(['git', '-C', str(project), 'ls-files', '-z', '--cached', '--others', '--exclude-standard']).decode().split('\0')
rules = [
    re.compile(r'TELEGRAM_API_' + r'HASH\s*=\s*[0-9a-fA-F]{32}\b'),
    re.compile(r'(?i)api_' + r'hash[\"\s:=>]+[\"\x27][0-9a-f]{32}[\"\x27]'),
    re.compile('-----BEGIN ' + '(?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
]
failures = []
for name in filter(None, names):
    path = project / name
    if path.suffix.lower() in {'.apk', '.aab', '.jks', '.keystore', '.session', '.db'}:
        failures.append(name)
        continue
    if not path.is_file() or path.suffix.lower() in {'.zip', '.jpg', '.png'}: continue
    try: content = path.read_text()
    except UnicodeDecodeError: continue
    if any(pattern.search(content) for pattern in rules): failures.append(name)
if failures:
    print('Possible secret/artifact in files (values redacted):', ', '.join(failures))
    sys.exit(1)
print('PASS: tracked/new source credential and private-artifact checks (heuristic; not a full audit)')
