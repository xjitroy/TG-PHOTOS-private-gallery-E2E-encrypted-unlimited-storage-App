#!/usr/bin/env python3
"""Verify new runtime/model pins without downloading private media or model weights."""
from pathlib import Path
import hashlib,json,zipfile,struct
root=Path(__file__).resolve().parents[1]
c=json.loads((root/'config/smart-models.json').read_text())
for f in c['files']:
 b=(root/f['path']).read_bytes();assert len(b)==f['bytes'] and hashlib.sha256(b).hexdigest()==f['sha256'],f['path']
 if f['path'].endswith('.so'):
  assert b[:5]==b'\x7fELF\x02'
  off=struct.unpack_from('<Q',b,32)[0];size,count=struct.unpack_from('<HH',b,54)
  for i in range(count):
   at=off+i*size
   if struct.unpack_from('<I',b,at)[0]==1:assert struct.unpack_from('<Q',b,at+48)[0]>=16384,f['path']
manifest=root/'app/src/main/assets/smart-models.json';assert hashlib.sha256(manifest.read_bytes()).hexdigest()==c['modelManifestSHA256']
rows=json.loads(manifest.read_text())['models'];assert [r['id'] for r in rows]==['small','balanced','qwen','embedding']
assert [sum(f['bytes'] for f in r['files']) for r in rows]==[235005792,545593888,737504352,484622336]
for r in rows:
 assert len(r['revision'])==40
 for f in r['files']:
  assert len(f['sha256'])==64 and f['path'].replace('-','').replace('_','').replace('.','').isalnum()
  assert f['url'].startswith('https://huggingface.co/'+r['repository']+'/resolve/'+r['revision']+'/')
for name in c['notices']:assert (root/'licenses'/name).read_bytes()==(root/'app/src/main/assets/third-party'/name).read_bytes()
with zipfile.ZipFile(root/'app/libs/litertlm-android-0.18.0-arm64.aar') as z:
 assert {p.split('/')[1] for p in z.namelist() if p.startswith('jni/') and p.endswith('.so')}=={'arm64-v8a'}
 assert b'minSdkVersion="24"' in z.read('AndroidManifest.xml')
manifest=(root/'app/src/main/AndroidManifest.xml').read_text();assert 'android:process=":intelligence" android:exported="false"' in manifest
print('PASS: immutable four-model pins, licensed ARM64 inference, 16KB native alignment and private process declaration')
