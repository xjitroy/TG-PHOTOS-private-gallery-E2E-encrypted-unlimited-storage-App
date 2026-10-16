#!/usr/bin/env python3
"""Supply-chain pin for the additional standard AES ZIP runtime library."""
from pathlib import Path
import hashlib
root = Path(__file__).resolve().parents[1]
jar = root / 'app/libs/zip4j-2.11.5.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest() == 'fee9a941f286166d4a19719d818f20e9f3addb3cbd7291c08b67f0e0ce75a9fd'
assert (root / 'licenses/Zip4j-Apache-2.0.txt').read_bytes() == (root / 'app/src/main/assets/third-party/Zip4j-Apache-2.0.txt').read_bytes()
assert 'implementation(files("libs/zip4j-2.11.5.jar"))' in (root / 'app/build.gradle.kts').read_text()
print('PASS: pinned Zip4j 2.11.5 SHA256, Apache notice and runtime inclusion')
