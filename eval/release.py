"""Bind offline evidence to the exact packaged application, without reading application secrets."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET


def revision(root):
    result = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else 'unknown'


def version(root):
    node = ET.parse(root / 'pom.xml').getroot()
    return node.find('{http://maven.apache.org/POM/4.0.0}version').text


def artifact(root):
    path = root / 'target' / ('mio-' + version(root) + '.jar')
    with zipfile.ZipFile(path) as jar:
        raw = jar.read('META-INF/build-info.properties').decode()
    properties = dict(line.split('=', 1) for line in raw.splitlines() if '=' in line and not line.startswith('#'))
    return {'file': path.relative_to(root).as_posix(), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'version': properties.get('build.version'), 'source_fingerprint': properties.get('build.sourceFingerprint'),
            'source_revision': properties.get('build.sourceRevision'), 'run_id': properties.get('build.evalRunId'),
            'java_version': properties.get('build.javaVersion')}


def matches(root, directory, run):
    try:
        saved = json.loads((directory / 'release.json').read_text())
        current = artifact(root)
        return saved == current and current['run_id'] == run['run_id'] \
            and current['source_fingerprint'] == run['source_fingerprint'] \
            and current['source_revision'] == run.get('source_revision') \
            and current['version'] == version(root) and current['java_version'] == '17'
    except (OSError, KeyError, ValueError, zipfile.BadZipFile, ET.ParseError):
        return False


def main():
    root = Path(__file__).resolve().parent.parent
    directory = root / 'target/evaluation'
    (directory / 'release.json').write_text(json.dumps(artifact(root), indent=2) + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
