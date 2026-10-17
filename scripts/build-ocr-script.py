#!/usr/bin/env python3
"""Rebuild the small owned JNI bridge. Tesseract runtime is pinned separately."""
from pathlib import Path
import argparse,subprocess
p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);a=p.parse_args()
r=Path(__file__).resolve().parents[1];tool=a.ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
for abi,target in [('arm64-v8a','aarch64-linux-android26'),('armeabi-v7a','armv7a-linux-androideabi26'),('x86','i686-linux-android26'),('x86_64','x86_64-linux-android26')]:
 out=r/'app/src/main/jniLibs'/abi/'libtgocrscript.so';out.parent.mkdir(parents=True,exist_ok=True)
 subprocess.run([str(tool/(target+'-clang')),'-shared','-fPIC','-O2','-Werror','-fvisibility=hidden','-Wl,-z,max-page-size=16384','-Wl,-z,relro,-z,now','-Wl,--build-id=sha1',str(r/'app/src/main/cpp/ocr_script.c'),'-ljnigraphics','-ldl','-o',str(out)],check=True)
 subprocess.run([str(tool/'llvm-strip'),'--strip-unneeded',str(out)],check=True)
 print('Built OCR script bridge:',abi,out.stat().st_size)
