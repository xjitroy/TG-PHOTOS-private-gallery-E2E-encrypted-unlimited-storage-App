#!/usr/bin/env python3
"""Scoped artifact checks; never a native-functionality or security audit claim."""
from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, zipfile

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=Path)
parser.add_argument('--sdk', type=Path, required=True)
parser.add_argument('--distribution', choices=['personal','public'], required=True)
parser.add_argument('--version-code', type=int, default=29)
parser.add_argument('--version-name', required=True)
parser.add_argument('--source-commit', help='Original compiled checkpoint for a sanitized public snapshot; checks its executable manifest too')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
apk = args.apk.resolve()
tools = args.sdk / 'build-tools/36.0.0'
def command(*items):
    return subprocess.check_output([str(i) for i in items], text=True)
assert apk.is_file() and apk.stat().st_size > 0
signing = command(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
package = 'app.televault.photos' if args.distribution == 'public' else 'app.televault'
certificate = (root / 'config/public-signing-cert.sha256').read_text().strip() if args.distribution == 'public' else '2fd27d17e3708fe40ec693d7887ec07e05cef67f782ddef9a5933a6a9d1d1e66'
assert certificate in signing, 'Pinned distribution signing identity changed'
badging = command(tools / 'aapt', 'dump', 'badging', apk)
assert f"name='{package}'" in badging
assert f"versionCode='{args.version_code}'" in badging and f"versionName='{args.version_name}'" in badging
assert 'application-debuggable' not in badging
if args.distribution == 'public':
    assert 'CN=Android Debug' not in signing
manifest = command(tools / 'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml')
assert 'android:allowBackup(0x01010280)=(type 0x12)0x0' in manifest
assert any('android:usesCleartextTraffic' in line and '(type 0x12)0x0' in line for line in manifest.splitlines())
assert package+'.private-media' in manifest and package+'.files' in manifest
# aapt badging omits launchable activity-alias entries on this toolchain; inspect
# the actual compiled manifest rather than weakening/removing the launcher check.
aliases = re.split(r'\n      E: ', manifest)
for name in ['DefaultLauncher','GalleryLauncher','PhotosLauncher','AlbumLauncher','FilesLauncher']:
    block = next((part for part in aliases if part.startswith('activity-alias ') and f'"{package}.{name}"' in part), None)
    assert block is not None, name+' is missing'
    assert 'android.intent.action.MAIN' in block and 'android.intent.category.LAUNCHER' in block
    assert 'android:targetActivity' in block and 'android:exported(0x01010010)=(type 0x12)0xffffffff' in block
    expected = '0xffffffff' if name == 'DefaultLauncher' else '0x0'
    assert 'android:enabled(0x0101000e)=(type 0x12)'+expected in block, name+' has wrong initial state'

checkout = command('git', '-C', root, 'rev-parse', 'HEAD').strip()
head = args.source_commit or checkout
assert re.fullmatch('[0-9a-f]{40}',head)
if args.source_commit:
    source = json.loads((root/'PUBLIC_SOURCE_MANIFEST.json').read_text())
    assert source['compiledSourceCommit'] == head
    for entry in source['files']:
        relative = Path(entry['path'])
        assert not relative.is_absolute() and '..' not in relative.parts
        assert hashlib.sha256((root/relative).read_bytes()).hexdigest() == entry['sha256'], str(relative)+' differs from compiled source'

abis = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
native = {}
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    assert not any(n.startswith('assets/') and any(token in n.lower() for token in ['.p12','.jks','.keystore','local.properties','release-secrets','session.sqlite']) for n in z.namelist())
    assert {'AndroidManifest.xml', 'resources.arsc', 'classes.dex'} <= set(z.namelist())
    vcs = z.read('META-INF/version-control-info.textproto').decode()
    assert head in vcs, 'APK does not embed the frozen source checkpoint'
    dex = b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
    assert b'Lorg/drinkless/tdlib/JsonClient;' in dex
    assert b'Lapp/televault/data/DriveDatabase_Impl;' in dex
    assert {n.split('/')[1] for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')} == abis
    for abi in sorted(abis):
        name = f'lib/{abi}/libtdjsonjava.so'
        data = z.read(name)
        assert data == (root / 'core/telegram/src/main/jniLibs' / abi / 'libtdjsonjava.so').read_bytes()
        native[abi] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    for notice in (['Zip4j-Apache-2.0.txt'] if args.version_code >= 19 else []) + ['TDLib-Boost-1.0.txt', 'OpenSSL-Apache-2.0.txt', 'AndroidX-Apache-2.0.txt', 'BouncyCastle-0.txt']:
        assert f'assets/third-party/{notice}' in z.namelist()
subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '-v', '4', str(apk)], check=True, stdout=subprocess.DEVNULL)
report = {'apk': apk.name, 'bytes': apk.stat().st_size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'versionCode': args.version_code, 'versionName': args.version_name, 'package': package, 'distribution': args.distribution, 'sourceCommit': head, 'sourceTree': command('git', '-C', root, 'rev-parse', 'HEAD^{tree}').strip(), 'certificateSHA256': certificate, 'debuggable': False, 'productionVerified': False, 'nativeLibraries': native, 'checks': ['ZIP CRC', 'signature and pinned distribution certificate', 'non-debuggable, backup/cleartext disabled manifest', 'four pinned ABI byte equality', '16 KB ZIP alignment', 'shrunk JNI/Room entry points', 'frozen source VCS checkpoint', 'bundled dependency notices'], 'nativeAcceptance': 'Authorized but unavailable in this workspace: native phone/account/provider/background-worker/performance/security acceptance. See PRODUCTION_READINESS.md for actual release gates.'}
apk.with_suffix('.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
