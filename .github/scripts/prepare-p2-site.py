#!/usr/bin/env python3
"""Validate Tycho's p2 site and repack the offline ZIP for Eclipse/EDT.

Run only as part of an expressly authorized manual build. The standalone
site and the ZIP must contain the same exact feature/plugins/metadata.
Prefer the traditional jar-backed p2 index for compatibility with local ZIP.
"""
import hashlib
import io
import os
from pathlib import Path
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET

EXPECTED_BUNDLES = {'core', 'platform', 'edt', 'ui'}
FEATURE = 'org.xander.edt.storagebridge.feature'
PREFIX = 'org.xander.edt.storagebridge.'
INDEX = (b'version=1\n'
         b'metadata.repository.factory.order=content.xml,\\!\n'
         b'artifact.repository.factory.order=artifacts.xml,\\!\n')


def fail(message):
    raise RuntimeError(message)


def jar_xml(data: bytes, filename: str) -> ET.Element:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return ET.fromstring(archive.read(filename))


def validate(payloads):
    paths = set(payloads)
    for necessary in ('p2.index', 'content.jar', 'artifacts.jar'):
        if necessary not in paths:
            fail(f'Missing p2 metadata: {necessary}')
    if payloads['p2.index'] != INDEX:
        fail('p2.index must explicitly prefer standard jar-backed repositories')
    content = jar_xml(payloads['content.jar'], 'content.xml')
    artifacts = jar_xml(payloads['artifacts.jar'], 'artifacts.xml')
    if content.find('units') is None or artifacts.find('artifacts') is None:
        fail('Missing installable-unit or artifact index')
    units = {(item.attrib['id'], item.attrib['version'])
             for item in content.find('units') if item.tag == 'unit'}
    descriptors = list(artifacts.find('artifacts'))
    declared = {}
    for item in descriptors:
        cls = item.attrib['classifier']
        artifact_id = item.attrib['id']
        version = item.attrib['version']
        if cls == 'osgi.bundle' and artifact_id.startswith(PREFIX):
            sub = 'plugins'
        elif cls == 'org.eclipse.update.feature' and artifact_id == FEATURE:
            sub = 'features'
        else:
            fail(f'Unexpected/unlicensed artifact in public site: {cls} {artifact_id}')
        path = f'{sub}/{artifact_id}_{version}.jar'
        if path in declared or path not in paths:
            fail(f'Missing/duplicate p2 payload {path}')
        props = {p.attrib['name']: p.attrib['value'] for p in item.find('properties')}
        raw = payloads[path]
        if str(len(raw)) != props.get('download.size'):
            fail(f'Artifact length mismatch {path}')
        if hashlib.sha256(raw).hexdigest() != props.get('download.checksum.sha-256'):
            fail(f'Artifact SHA-256 mismatch {path}')
        unit_id = artifact_id + '.feature.jar' if cls == 'org.eclipse.update.feature' else artifact_id
        if (unit_id, version) not in units:
            fail(f'Artifact lacks matching installable unit {path}')
        declared[path] = (artifact_id, version)
    expected_ids = {PREFIX + name for name in EXPECTED_BUNDLES} | {FEATURE}
    if {aid for aid, _ in declared.values()} != expected_ids:
        fail('The four bridge bundles and feature must all be present')
    versions = {version for _, version in declared.values()}
    if len(versions) != 1:
        fail('Feature and bundle versions do not agree')
    version = next(iter(versions))
    if (FEATURE + '.feature.group', version) not in units:
        fail('Installable EDT Storage Bridge feature group is missing')
    if 'plugins/' not in paths or 'features/' not in paths:
        fail('Site must contain plugin/ and feature/ directory entries')
    return version, sorted(declared)


def main(site_dir, archive_path):
    site = Path(site_dir).resolve()
    archive = Path(archive_path).resolve()
    if not site.is_dir() or not archive.is_file():
        fail('Tycho p2 repository and its archive must already exist')
    (site / 'p2.index').write_bytes(INDEX)
    with zipfile.ZipFile(archive) as old:
        names = old.namelist()
        if len(names) != len(set(names)):
            fail('Duplicate filenames in generated p2 ZIP')
        for entry in old.infolist():
            if entry.filename.startswith('/') or '..' in Path(entry.filename).parts:
                fail('Unsafe p2 archive entry')
        site_files = {p.relative_to(site).as_posix(): p.read_bytes()
                      for p in site.rglob('*') if p.is_file()}
        site_files['plugins/'] = b''
        site_files['features/'] = b''
        site_version, _ = validate(site_files)
        original_files = {name: old.read(name) for name in names}
        if set(original_files) != set(site_files):
            extra = set(original_files) - set(site_files)
            missing = set(site_files) - set(original_files)
            fail(f'Site/archive mismatch: extra={extra}, missing={missing}')
        for name, raw in original_files.items():
            if name != 'p2.index' and raw != site_files[name]:
                fail(f'ZIP does not match actual Tycho site: {name}')
    fd, temp_name = tempfile.mkstemp(prefix=archive.stem + '-', suffix='.zip', dir=archive.parent)
    os.close(fd)
    try:
        with zipfile.ZipFile(temp_name, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
            for name in names:
                zipped.writestr(name, site_files[name])
        with zipfile.ZipFile(temp_name) as verified:
            bad_entry = verified.testzip()
            if bad_entry is not None:
                fail(f'ZIP CRC check failed: {bad_entry}')
            version, artifacts = validate({name: verified.read(name)
                                           for name in verified.namelist()})
            if version != site_version:
                fail('ZIP version differs from site')
        os.replace(temp_name, archive)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)
    print(f'Validated and repackaged p2 ZIP: {archive.name}, version {version}')
    for path in artifacts:
        print(' ', path)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit('Usage: prepare-p2-site.py <target/repository> <p2-archive.zip>')
    try:
        main(sys.argv[1], sys.argv[2])
    except (OSError, ValueError, RuntimeError, KeyError, zipfile.BadZipFile) as ex:
        sys.exit(f'Invalid p2 update site: {ex}')
