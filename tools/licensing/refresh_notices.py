"""Review the resolved Maven artifacts and preserve their published notices.

Run Gradle with -I tools/licensing/export_release.gradle first. This command
updates the reviewed inventory; ordinary builds only verify it. Unknown license
terms are rejected instead of being guessed from the artifact's package name.
"""
# Copyright (c) 2026 abn3li
# SPDX-License-Identifier: GPL-3.0-only
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'TeleMusic-license-inventory/1.0'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'Source/metadata unavailable: {url}: HTTP {error.code}') from error


def pom_licenses(pom, depth=0):
    declared = [node.findtext('{*}name', '') for node in pom.findall('{*}licenses/{*}license')]
    parent = pom.find('{*}parent')
    if declared or parent is None:
        return declared
    assert depth < 4, 'POM parent chain needs manual review'
    group, name, version = [parent.findtext('{*}' + key) for key in ['groupId', 'artifactId', 'version']]
    cached = list((CACHE / group / name / version).rglob('*.pom'))
    if cached:
        parent_pom = ET.parse(cached[0]).getroot()
    else:
        url = 'https://repo.maven.apache.org/maven2/' + group.replace('.', '/') + f'/{name}/{version}/{name}-{version}.pom'
        parent_pom = ET.fromstring(fetch(url))
    return pom_licenses(parent_pom, depth + 1)


def collect_zip(data, notices, copyrights):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in archive.namelist():
            if name.endswith('/'):
                continue
            if re.search(r'(^|/)(LICENSE|NOTICE|COPYING|COPYRIGHT)([._-][^/]*)?$', name, re.I):
                text = archive.read(name).decode('utf-8', errors='replace')
                if text.strip():
                    notices.add(text)
            elif name.endswith('classes.jar'):
                collect_zip(archive.read(name), notices, copyrights)
            elif name.endswith(('.java', '.kt')):
                text = archive.read(name).decode('utf-8', errors='replace')
                header = text[:min(len(text), 5000)]
                for line in header.splitlines():
                    if re.search(r'copyright\s*(\([Cc]\)|[0-9])', line, re.I):
                        copyrights.add(line.strip().strip('/* ').strip())
                # BSD/MIT notices in shaded sources need their entire conditions,
                # not just the Apache license of the enclosing Maven artifact.
                first = re.search(r'/\*.*?\*/', header, re.S)
                if first and re.search(r'Redistribution and use|Permission is hereby granted', first.group()):
                    notices.add(first.group())


def review(artifact):
    group, name, version = (artifact[key] for key in ['group', 'name', 'version'])
    coordinate = f'{group}:{name}:{version}'
    path = Path(artifact['file'])
    assert path.suffix in ['.aar', '.jar'], f'Review non-Maven component separately: {path}'
    poms = list((CACHE / group / name / version).rglob('*.pom'))
    assert len(poms) == 1, f'Missing or ambiguous POM: {coordinate}'
    pom = ET.parse(poms[0]).getroot()
    declared = pom_licenses(pom)
    repositories = ['https://dl.google.com/dl/android/maven2'] if group.startswith('androidx.') else ['https://repo.maven.apache.org/maven2']
    if group == 'com.github.tdlibx':
        repositories = ['https://jitpack.io']
    filename = f'{name}-{version}-sources.jar'
    relative = group.replace('.', '/') + f'/{name}/{version}/{filename}'
    local_sources = list((CACHE / group / name / version).rglob(filename))
    source = local_sources[0].read_bytes() if local_sources else None
    source_url = repositories[0] + '/' + relative
    with zipfile.ZipFile(path) as published:
        empty_artifact = not any(name.endswith('.class') for name in published.namelist()) and path.suffix == '.jar'
    if source is None and not empty_artifact:
        source = fetch(source_url)
    notices, copyrights = set(), set()
    collect_zip(path.read_bytes(), notices, copyrights)
    if source is not None:
        collect_zip(source, notices, copyrights)
    joined = '\n'.join(notices)
    # Guava uses the parent POM's license. Its published source jar carries
    # the full Apache license, which is evidence even when the child POM is empty.
    apache = declared and all('apache' in item.lower() and '2' in item for item in declared)
    apache = apache or (not declared and 'Apache License' in joined and 'Version 2.0' in joined)
    assert apache, f'License needs manual review: {coordinate}: {declared}'
    destination = ROOT / 'build/licensing/sources' / f'{group}-{filename}'
    destination.parent.mkdir(parents=True, exist_ok=True)
    if source is not None:
        destination.write_bytes(source)
    notice_name = f'{group}-{name}-{version}.txt'
    notice_path = ROOT / 'licenses/maven' / notice_name
    notice_path.parent.mkdir(parents=True, exist_ok=True)
    notice_text = coordinate + '\nApache License 2.0\n\n' + '\n'.join(sorted(copyrights)) + '\n\n'
    notice_text += '\n\n'.join(sorted(notices)) if notices else (ROOT / 'licenses/Apache-2.0.txt').read_text(encoding='utf-8')
    notice_path.write_text(notice_text.rstrip() + '\n', encoding='utf-8', newline='\n')
    return {'coordinate': coordinate, 'artifact_sha256': sha(path.read_bytes()), 'license': 'Apache-2.0', 'declared_licenses': declared, 'notice': notice_path.relative_to(ROOT).as_posix(), 'notice_sha256': sha(notice_path.read_bytes()), 'source_url': source_url if source is not None else None, 'source_sha256': sha(source) if source is not None else None, 'source_file': destination.relative_to(ROOT).as_posix() if source is not None else None, 'empty_artifact': empty_artifact}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, default=ROOT / 'build/licensing/release-components.json')
    args = parser.parse_args()
    artifacts = json.loads(args.inventory.read_text(encoding='utf-8'))
    with ThreadPoolExecutor(max_workers=6) as executor:
        reviewed = sorted(executor.map(review, artifacts), key=lambda x: x['coordinate'])
    (ROOT / 'licenses/maven-components.json').write_text(json.dumps(reviewed, indent=2) + '\n', encoding='utf-8')
    print(f'Reviewed {len(reviewed)} release artifacts and preserved their source/copyright/license notices.')


if __name__ == '__main__':
    main()
