#!/usr/bin/env python3
"""Pinned, licensed, bundled offline models/runtime. No downloads or owner data."""
from pathlib import Path
import hashlib,json,zipfile,struct
root=Path(__file__).resolve().parents[1]
config=json.loads((root/'config/people-models.json').read_text())
for row in config['files']:
 data=(root/row['path']).read_bytes()
 assert len(data)==row['bytes'] and hashlib.sha256(data).hexdigest()==row['sha256'],row['path']
for notice in ['MobileFaceNet-Apache-2.0.txt','MediaPipe-Apache-2.0.txt','Qualcomm-BSD-3-Clause.txt','LiteRT-Apache-2.0.txt']:
 assert (root/'licenses'/notice).read_bytes()==(root/'app/src/main/assets/third-party'/notice).read_bytes()
with zipfile.ZipFile(root/'app/libs/litert-1.4.1.aar') as aar:
 for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']:
  data=aar.read('jni/'+abi+'/libLiteRt.so') if 'jni/'+abi+'/libLiteRt.so' in aar.namelist() else aar.read('jni/'+abi+'/libtensorflowlite_jni.so')
  assert data[:4]==b'\x7fELF'
  is64=data[4]==2;endian='<' if data[5]==1 else '>'
  offset=struct.unpack_from(endian+('Q' if is64 else 'I'),data,32 if is64 else 28)[0]
  size,count=struct.unpack_from(endian+'HH',data,54 if is64 else 42)
  for i in range(count):
   at=offset+i*size;kind=struct.unpack_from(endian+'I',data,at)[0]
   if kind==1:assert struct.unpack_from(endian+('Q' if is64 else 'I'),data,at+(48 if is64 else 28))[0]>=16384
print('PASS: pinned offline People models, licenses and four-ABI 16 KB runtime')
