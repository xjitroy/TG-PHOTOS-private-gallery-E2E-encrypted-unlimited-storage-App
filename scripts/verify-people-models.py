#!/usr/bin/env python3
"""Verify the licensed runtime and immutable hash-pinned optional face model manifest."""
from pathlib import Path
import hashlib,json,zipfile,struct
root=Path(__file__).resolve().parents[1]
config=json.loads((root/'config/people-models.json').read_text())
for row in config['files']:
 data=(root/row['path']).read_bytes()
 assert len(data)==row['bytes'] and hashlib.sha256(data).hexdigest()==row['sha256'],row['path']
manifest=json.loads((root/'app/src/main/assets/people-models.json').read_text())
assert manifest['modelId']=='opencv-yunet-sface-0ba9fbfa-v2'
assert {p['bytes'] for p in manifest['models']}=={232589,38696353}
for p in manifest['models']:
 assert p['url'].startswith('https://media.githubusercontent.com/media/opencv/opencv_zoo/'+manifest['upstreamCommit']+'/models/')
 assert len(p['sha256'])==64 and p['path'].endswith('.onnx') and '/' not in p['path']
for notice in ['SFace-Apache-2.0.txt','YuNet-MIT.txt','ONNXRuntime-MIT.txt','ONNXRuntime-ThirdPartyNotices.txt']:
 assert (root/'licenses'/notice).read_bytes()==(root/'app/src/main/assets/third-party'/notice).read_bytes()
with zipfile.ZipFile(root/'app/libs/onnxruntime-android-1.22.0.aar') as aar:
 for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']:
  for lib in ['libonnxruntime.so','libonnxruntime4j_jni.so']:
   data=aar.read('jni/'+abi+'/'+lib);assert data[:4]==b'\x7fELF'
   is64=data[4]==2;endian='<' if data[5]==1 else '>'
   offset=struct.unpack_from(endian+('Q' if is64 else 'I'),data,32 if is64 else 28)[0]
   size,count=struct.unpack_from(endian+'HH',data,54 if is64 else 42)
   for i in range(count):
    at=offset+i*size;kind=struct.unpack_from(endian+'I',data,at)[0]
    if kind==1:assert struct.unpack_from(endian+('Q' if is64 else 'I'),data,at+(48 if is64 else 28))[0]>=(16384 if is64 else 4096)
print('PASS: licensed optional YuNet/SFace manifest and pinned four-ABI ONNX runtime (64-bit 16 KB alignment)')
