#!/usr/bin/env python3
import hashlib
from pathlib import Path
import re
import sys

project = Path(__file__).resolve().parents[1]
expected = (project / 'docs/tdlib-auth-schema.tl').read_text().splitlines()
schema_path = Path(sys.argv[1]) if len(sys.argv) > 1 else project / 'docs/tdlib-auth-schema.tl'
schema = schema_path.read_bytes()
actual = schema.decode().splitlines()
if len(sys.argv) > 1:
    blob = b'blob ' + str(len(schema)).encode() + b'\0' + schema
    assert hashlib.sha1(blob).hexdigest() == 'd96a259c6f932b9961bb5e025afb60fe17ccc29e', 'Wrong TDLib schema blob'
for declaration in expected:
    if declaration.strip():
        assert declaration in actual, f'TDLib declaration changed: {declaration.split()[0]}'
policy = (project / 'core/domain/src/main/java/app/televault/domain/AuthProtocol.java').read_text()
for state in re.findall(r'case "(authorizationState\w+)"', policy):
    assert any(line.startswith(state + ' ') for line in actual), f'Unsupported state mapping: {state}'
for method in re.findall(r'-> "(\w+)";', policy):
    assert any(line.startswith(method + ' ') for line in actual), f'Unsupported method: {method}'
print('PASS: pinned TDLib auth declarations and production state/method mapping')
