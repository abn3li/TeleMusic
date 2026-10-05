"""Verify reviewed licenses, generate offline notices and bind source to an APK."""
# Copyright (c) 2026 abn3li
# SPDX-License-Identifier: GPL-3.0-only
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import html
import io
import json
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
if not __debug__:
    raise RuntimeError('Licensing verification requires Python without optimization (-O or PYTHONOPTIMIZE).')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_json(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8'))


def project_files():
    roots = ('app/src/', 'third_party/', 'tools/licensing/', 'licenses/', 'gradle/')
    files = {'app/build.gradle.kts', 'app/proguard-rules.pro', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'LICENSE', 'COPYING', 'OPENSSL_PERMISSION', 'README.md', 'BUILDING.md', 'THIRD_PARTY_NOTICES.md', '.gitignore', '.gitattributes'}
    result = None
    if (ROOT / '.git').exists():
        try:
            result = subprocess.run(['git', 'ls-files', '--cached', '--others', '--exclude-standard'], cwd=ROOT, text=True, capture_output=True)
        except FileNotFoundError:
            pass
    if result is not None and result.returncode == 0:
        names = result.stdout.splitlines()
    else:
        # Corresponding-source ZIPs intentionally exclude .git. They must still
        # build and reproduce the same source snapshot after extraction.
        names = list(files)
        for directory in roots:
            names.extend(path.relative_to(ROOT).as_posix() for path in (ROOT / directory).rglob('*') if path.is_file())
    return sorted(set(name for name in names if (name in files or name.startswith(roots)) and (ROOT / name).is_file() and not name.endswith(('.jks', '.keystore', '.apk', '.pyc', '.pyo')) and '__pycache__' not in Path(name).parts and Path(name).name != 'local.properties'))


def snapshot():
    hashes = {name: sha(ROOT / name) for name in project_files()}
    identity = hashlib.sha256(json.dumps(hashes, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return {'sha256': identity, 'files': hashes}


def verify(inventory):
    reviewed = read_json('licenses/maven-components.json')
    actual = {f"{a['group']}:{a['name']}:{a['version']}": a for a in json.loads(inventory.read_text(encoding='utf-8'))}
    assert set(actual) == {a['coordinate'] for a in reviewed}, 'Release dependencies changed. Refresh and review the licensing inventory.'
    for record in reviewed:
        assert sha(actual[record['coordinate']]['file']) == record['artifact_sha256'], f"Artifact changed: {record['coordinate']}"
        assert sha(ROOT / record['notice']) == record['notice_sha256'], f"Notice changed: {record['coordinate']}"
    for source in read_json('third_party/sources.json')['sources']:
        assert sha(ROOT / source['path']) == source['sha256'], f"Vendored source changed: {source['path']}"
    for component in read_json('licenses/native-source-components.json'):
        for path, expected in component['license_sha256'].items():
            assert sha(ROOT / path) == expected, f'Native/runtime notice changed: {path}'
    native = read_json('third_party/media3-ffmpeg/build-manifest.json')
    assert native['gpl_components'] is False and native['nonfree_components'] is False
    assert set(native['abis']) == {'arm64-v8a', 'x86_64'}
    assert sha(ROOT / 'tools/licensing/build_ffmpeg.py') == native['recipe_sha256'], 'Rebuild FFmpeg after changing its recipe.'
    assert sha(ROOT / 'third_party/media3-ffmpeg/src/main/jni/CMakeLists.txt') == native['cmake_sha256'], 'Rebuild FFmpeg after changing its JNI build.'
    for abi, record in native['abis'].items():
        assert sha(ROOT / record['file']) == record['sha256'], f'Unreviewed native decoder: {abi}'
    assert not (ROOT / 'app/libs/media3-ffmpeg-audio.aar').exists(), 'Unidentified old decoder AAR is still present.'
    assert not (ROOT / 'app/src/main/jniLibs/armeabi-v7a/libffmpegJNI.so').exists(), 'Unidentified old ARM32 decoder is still present.'
    build = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
    assert 'yt-dlp==2026.8.19' in build and 'mutagen==1.48.1' in build, 'Python dependencies changed; update their source/notices.'
    assert 'id("com.chaquo.python") version "17.0.0"' in (ROOT / 'build.gradle.kts').read_text(encoding='utf-8'), 'Review the updated Python runtime.'
    return reviewed


def generate_assets(directory, reviewed):
    directory.mkdir(parents=True, exist_ok=True)
    source_snapshot = snapshot()
    parts = ['<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">',
             '<style>body{font:16px -apple-system,system-ui,sans-serif;line-height:1.5;margin:20px;background:transparent;color:#1c1c1e}a{color:#0a84ff}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:12px monospace}details{border-top:1px solid #8885;padding:14px 0}summary{font-weight:600}body.dark{color:#f2f2f7}small{opacity:.65}</style></head><body>',
             '<h1>TeleMusic</h1><p>Copyright © 2026 abn3li.</p><p>Free software under GNU GPL version 3 only, with the additional OpenSSL linking permission below. You may redistribute and modify it under these terms. There is no warranty.</p>',
             '<p>The matching corresponding-source archive is supplied alongside each distributed APK. <a href="https://github.com/abn3li/TeleMusic">Project and releases</a>.</p>',
             '<p>This product includes software developed by the OpenSSL Project for use in the OpenSSL Toolkit. This product includes cryptographic software written by Eric Young (eay@cryptsoft.com) and software written by Tim Hudson (tjh@cryptsoft.com).</p>']
    def section(title, text):
        parts.append('<details><summary>' + html.escape(title) + '</summary><pre>' + html.escape(text) + '</pre></details>')
    section('GNU GPL version 3', (ROOT / 'LICENSE').read_text(encoding='utf-8'))
    section('TeleMusic copyright and additional OpenSSL permission', (ROOT / 'COPYING').read_text(encoding='utf-8') + '\n\n' + (ROOT / 'OPENSSL_PERMISSION').read_text(encoding='utf-8'))
    section('Component notices and source information', (ROOT / 'THIRD_PARTY_NOTICES.md').read_text(encoding='utf-8'))
    for record in reviewed:
        section(record['coordinate'], (ROOT / record['notice']).read_text(encoding='utf-8'))
    for path in sorted((ROOT / 'licenses').rglob('*')):
        if path.is_file() and path.suffix != '.json' and 'maven' not in path.parts:
            section(path.relative_to(ROOT / 'licenses').as_posix(), path.read_text(encoding='utf-8', errors='replace'))
    parts.append('<p><small>Source snapshot: ' + source_snapshot['sha256'] + '</small></p></body></html>')
    # The light/dark variant is selected by the app; no JavaScript or network fetches.
    light = ''.join(parts)
    (directory / 'index.html').write_text(light, encoding='utf-8')
    (directory / 'dark.html').write_text(light.replace('<body>', '<body class="dark">', 1), encoding='utf-8')
    (directory / 'source-snapshot.json').write_text(json.dumps(source_snapshot, indent=2) + '\n', encoding='utf-8')
    print(f'Verified {len(reviewed)} release components; generated offline notices. Snapshot {source_snapshot["sha256"][:12]}.')


def ensure_source(record):
    if not record.get('source_file'):
        assert record.get('empty_artifact'), 'Missing source for a nonempty artifact.'
        return None
    path = ROOT / record['source_file']
    expected = record.get('source_sha256', record.get('sha256'))
    supplied = ROOT.parent / 'third-party-sources' / path.name
    if not path.exists() and supplied.is_file():
        assert sha(supplied) == expected, f'Supplied source archive changed: {supplied.name}'
        return supplied
    if not path.exists():
        url = record.get('source_url', record.get('url'))
        request = urllib.request.Request(url, headers={'User-Agent': 'TeleMusic-corresponding-source/1.0'})
        with urllib.request.urlopen(request, timeout=45) as response:
            data = response.read()
        assert hashlib.sha256(data).hexdigest() == expected, f'Source archive hash mismatch: {url}'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    assert sha(path) == expected, f'Source archive changed: {path.name}'
    return path


def runtime_payloads(android_package):
    payloads = {}
    for name in android_package.namelist():
        if name.endswith('.so') and not name.endswith('/libffmpegJNI.so'):
            payloads[name] = hashlib.sha256(android_package.read(name)).hexdigest()
        elif name.startswith('assets/chaquopy/stdlib-') and name.endswith('.imy'):
            with zipfile.ZipFile(io.BytesIO(android_package.read(name))) as stdlib:
                for member in stdlib.namelist():
                    if member.endswith('.so'):
                        payloads[name + '!/' + member] = hashlib.sha256(stdlib.read(member)).hexdigest()
    payloads['assets/chaquopy/cacert.pem'] = hashlib.sha256(android_package.read('assets/chaquopy/cacert.pem')).hexdigest()
    return payloads


def verify_runtime(payloads, reviewed):
    assert set(payloads) == set(reviewed['entries']), 'Native/Python runtime contents changed; review their source and licenses before release.'
    variants = reviewed.get('packaging_variants', {})
    for name, digest in payloads.items():
        accepted = [reviewed['entries'][name], *variants.get(name, [])]
        assert digest in accepted, f'Unreviewed native/Python runtime payload: {name}'


def package_source(apk, assets, reviewed):
    prepared = json.loads((assets / 'source-snapshot.json').read_text(encoding='utf-8'))
    current = snapshot()
    assert prepared == current, 'Sources changed during the APK build; rebuild before packaging.'
    with zipfile.ZipFile(apk) as android_package:
        actual = json.loads(android_package.read('assets/licenses/source-snapshot.json'))
        assert actual == current, 'APK notices belong to a different source snapshot.'
        runtime = read_json('licenses/packaged-runtime.json')
        packaged_runtime = runtime_payloads(android_package)
        verify_runtime(packaged_runtime, runtime)
        for abi, record in read_json('third_party/media3-ffmpeg/build-manifest.json')['abis'].items():
            packaged = android_package.read(f'lib/{abi}/libffmpegJNI.so')
            # Verify that packaging retained the reviewed, already stripped binary.
            assert hashlib.sha256(packaged).hexdigest() == record['sha256'], f'Packaged decoder differs: {abi}'
        with zipfile.ZipFile(io.BytesIO(android_package.read('assets/chaquopy/requirements-common.imy'))) as requirements:
            for name, version in [('mutagen', '1.48.1'), ('yt_dlp', '2026.8.19')]:
                metadata = requirements.read(f'{name}-{version}.dist-info/METADATA').decode()
                assert f'Version: {version}' in metadata, f'Wrong Python package: {name}'
    upstream = read_json('licenses/native-source-components.json')
    with ThreadPoolExecutor(max_workers=4) as executor:
        source_paths = list(executor.map(ensure_source, [*reviewed, *upstream]))
    # Multiple Maven variants can share one source archive. Include it once,
    # and reject conflicting names rather than create ambiguous ZIP entries.
    unique_sources = {}
    for path in source_paths:
        if path:
            if path.name in unique_sources:
                assert sha(path) == sha(unique_sources[path.name]), f'Conflicting source archive name: {path.name}'
            else:
                unique_sources[path.name] = path
    destination = apk.parent / 'app-release-corresponding-source.zip'
    head = None
    if (ROOT / '.git').exists():
        try:
            git = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True, capture_output=True)
            head = git.stdout.strip() if git.returncode == 0 else None
        except FileNotFoundError:
            pass
    manifest = {'apk_sha256': sha(apk), 'source_snapshot': current['sha256'], 'source_files': current['files'], 'maven_components': reviewed, 'native_source_components': upstream, 'packaged_runtime': runtime, 'packaged_runtime_actual_sha256': packaged_runtime, 'ffmpeg_build': read_json('third_party/media3-ffmpeg/build-manifest.json'), 'git_head': head, 'working_tree_included': True, 'excluded_private_material': ['local.properties', 'signing keystores', 'Git credentials and .git data']}
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in current['files']:
            archive.write(ROOT / name, 'TeleMusic/' + name)
        for path in unique_sources.values():
            archive.write(path, 'third-party-sources/' + path.name)
        archive.writestr('RELEASE-SOURCE-MANIFEST.json', json.dumps(manifest, indent=2) + '\n')
        archive.writestr('README.txt', 'This archive contains the exact working sources and build materials for the APK SHA-256 in RELEASE-SOURCE-MANIFEST.json. Start with TeleMusic/BUILDING.md. Native and Python sources are included; Maven source jars and native/runtime source archives are in third-party-sources. Signing keys, account credentials, local.properties and .git are excluded. Publish this source archive beside its matching APK.\n')
    report = {'apk': str(apk), 'apk_sha256': sha(apk), 'source_archive': str(destination), 'source_archive_sha256': sha(destination), 'source_snapshot': current['sha256'], 'release_components': len(reviewed), 'native_runtime_payloads': len(runtime['entries']), 'source_files': len(current['files']), 'verified': True}
    (apk.parent / 'licensing-verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('APK/source/notices verified. Corresponding source: ' + str(destination))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    reviewed = verify(args.inventory)
    if args.apk:
        package_source(args.apk, args.assets, reviewed)
    else:
        generate_assets(args.assets, reviewed)


if __name__ == '__main__':
    main()
