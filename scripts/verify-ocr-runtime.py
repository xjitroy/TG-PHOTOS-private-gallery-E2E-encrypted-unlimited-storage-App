#!/usr/bin/env python3
"""Pinned opt-in OCR runtime/models and licensed 64-bit 16 KB ELF support, offline."""
from pathlib import Path
import hashlib,json,struct,zipfile
r=Path(__file__).resolve().parents[1];config=json.loads((r/'config/ocr-models.json').read_text())
assert (r/'config/ocr-models.json').read_bytes()==(r/'app/src/main/assets/ocr-models.json').read_bytes()
assert {p['id'] for p in config['models']}=={'ben','hin','eng','mar','tam','tel','ara','spa','osd'}
assert sum(p['bytes'] for p in config['models'])==28506746
for p in config['models']:
 assert p['url']==f"https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/{config['sourceCommit']}/{p['path']}"
 assert len(p['sha256'])==64 and p['bytes']>0
for p in config['files']:
 b=(r/p['path']).read_bytes();assert len(b)==p['bytes'] and hashlib.sha256(b).hexdigest()==p['sha256']
for notice in ['Tesseract4Android-Apache-2.0.txt','Tesseract-Apache-2.0.txt','Leptonica-BSD.txt','Libpng-License.txt','Libjpeg-IJG.txt']:
 assert (r/'licenses'/notice).read_bytes()==(r/'app/src/main/assets/third-party'/notice).read_bytes()
with zipfile.ZipFile(r/'app/libs/tesseract4android-4.9.0.aar') as z:
 for name in z.namelist():
  if not name.startswith('jni/') or not name.endswith('.so'):continue
  b=z.read(name);assert b[:4]==b'\x7fELF'
  if b[4]!=2:continue # 16 KB Android memory pages apply to the 64-bit ABIs.
  off=struct.unpack_from('<Q',b,32)[0];size,count=struct.unpack_from('<HH',b,54)
  for i in range(count):
   at=off+i*size
   if struct.unpack_from('<I',b,at)[0]==1:assert struct.unpack_from('<Q',b,at+48)[0]>=16384
assert not list((r/'app/src/main/assets').rglob('*.traineddata')), 'Downloadable language models must not duplicate APK storage'
print('PASS: pinned eight-language OCR, owned JNI bridge, licenses and 64-bit 16 KB runtime')
