#!/usr/bin/env python3
"""Reproduce the pinned ARM64 CPU runtime/Java API derivative and owned API26 JNI bridge.
Only public dependencies are downloaded. Requires an Android NDK r28c installation.
"""
from pathlib import Path
import argparse,hashlib,json,io,tarfile,zipfile,urllib.request,subprocess,tempfile,shutil
p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);a=p.parse_args()
r=Path(__file__).resolve().parents[1];c=json.loads((r/'config/smart-models.json').read_text());tool=a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
def fetch(url,sha):
 with urllib.request.urlopen(url,timeout=60) as response:b=response.read()
 assert hashlib.sha256(b).hexdigest()==sha,'Upstream integrity failure';return b
stage=tempfile.TemporaryDirectory(prefix='tg-smart-runtime-');work=Path(stage.name)
native=work/'native';native.mkdir()
aar=work/'litertlm-android-0.18.0-arm64.aar'
archive=fetch(c['llamaArchiveUrl'],c['llamaArchiveSHA256']);needed={'libllama.so','libmtmd.so','libggml-base.so','libggml.so','libggml-cpu-android_armv8.0_1.so'}
with tarfile.open(fileobj=io.BytesIO(archive)) as t:
 for name in needed:
  matches=[m for m in t.getmembers() if Path(m.name).name==name and m.isfile()];assert len(matches)==1
  (native/name).write_bytes(t.extractfile(matches[0]).read());subprocess.run([str(tool/'llvm-strip'),'--strip-unneeded',str(native/name)],check=True)
# Preserve every upstream Java class byte. Omit only the incompatible Kotlin-module index
# and x86_64 JNI (the gallery's established x86_64 specialised engines are unaffected).
original=fetch(c['liteRtUrl'],c['liteRtUpstreamSHA256'])
with zipfile.ZipFile(io.BytesIO(original)) as z,zipfile.ZipFile(aar,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as out:
 for info in z.infolist():
  if info.filename.startswith('jni/x86_64/'):continue
  b=z.read(info)
  if info.filename=='classes.jar':
   target=io.BytesIO()
   with zipfile.ZipFile(io.BytesIO(b)) as jar,zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as java:
    for entry in jar.infolist():
     if not entry.filename.endswith('.kotlin_module'):java.writestr(entry,jar.read(entry))
   b=target.getvalue()
  out.writestr(info,b)
subprocess.run([str(tool/'clang++'),'--target=aarch64-linux-android26','--sysroot='+str(a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/sysroot'),'-std=c++17','-shared','-fPIC','-O2','-fvisibility=hidden','-static-libstdc++','-Wl,-z,max-page-size=16384,-z,relro,-z,now','--ld-path='+str(tool/'ld.lld'),'-I'+str(r/'app/src/main/cpp/vendor/llama-b11756'),str(r/'app/src/main/cpp/smart_vlm.cpp'),'-L'+str(native),'-lmtmd','-lllama','-lggml','-lggml-base','-ljnigraphics','-llog','-o',str(native/'libtgsmart.so')],check=True)
subprocess.run([str(tool/'llvm-strip'),'--strip-unneeded',str(native/'libtgsmart.so')],check=True)
for f in c['files']:
 p=aar if f['path'].endswith('.aar') else native/Path(f['path']).name
 b=p.read_bytes();assert hashlib.sha256(b).hexdigest()==f['sha256'],f['path']+' differs; review compiler/build provenance before updating pins'
for f in c['files']:
 p=aar if f['path'].endswith('.aar') else native/Path(f['path']).name
 shutil.copy2(p,r/f['path'])
stage.cleanup()
print('PASS: reproduced hash-pinned ARM64 CPU runtime and Java API derivative')
