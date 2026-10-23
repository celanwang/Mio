import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile
from eval.acceptance import assess, begin, fingerprint, PG_SUITES, write


class AcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); (self.root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>6.0.0</version></project>')
        self.directory = self.root / 'target/evaluation'
        write(self.directory / 'acceptance-run.json', {'run_id': 'current', 'source_fingerprint': fingerprint(self.root), 'source_revision': 'fixture-revision'})
        write(self.directory / 'python-tests.json', {'run_id': 'current', 'tests': 5, 'failures': 0, 'errors': 0, 'skipped': 0})
        write(self.directory / 'promotion.json', {'status': 'insufficient_or_failed_evidence'})
        jar = self.root / 'target/mio-6.0.0.jar'
        with zipfile.ZipFile(jar, 'w') as output:
            output.writestr('META-INF/build-info.properties',
                            'build.version=6.0.0\nbuild.javaVersion=17\nbuild.sourceRevision=fixture-revision\nbuild.evalRunId=current\nbuild.sourceFingerprint=' + fingerprint(self.root) + '\n')
        from eval.release import artifact
        write(self.directory / 'release.json', artifact(self.root))
        reports = self.root / 'target/surefire-reports'; reports.mkdir(parents=True)
        for name in PG_SUITES:
            node = ET.Element('testsuite', name='test.' + name, tests='1', failures='0', errors='0', skipped='0')
            properties = ET.SubElement(node, 'properties'); ET.SubElement(properties, 'property', name='mio.eval.run-id', value='current')
            ET.ElementTree(node).write(reports / ('TEST-' + name + '.xml'))

    def test_complete_requires_all_real_pg_suites_and_does_not_establish_qualification(self):
        report = assess(self.root, self.directory)
        self.assertEqual(report['status'], 'OFFLINE_COMPLETE'); self.assertEqual(report['qualification'], 'NOT_ESTABLISHED')
        path = next((self.root / 'target/surefire-reports').glob('TEST-*.xml')); tree = ET.parse(path); tree.getroot().set('skipped', '1'); tree.write(path)
        self.assertEqual(assess(self.root, self.directory)['status'], 'POSTGRES_REQUIRED')

    def test_stale_java_or_python_reports_cannot_pass(self):
        path = next((self.root / 'target/surefire-reports').glob('TEST-*.xml')); tree = ET.parse(path)
        tree.getroot().find('properties/property').set('value', 'old'); tree.write(path)
        self.assertIn('current_run_only', assess(self.root, self.directory)['failed_checks'])
        write(self.directory / 'python-tests.json', {'run_id': 'old', 'tests': 5, 'failures': 0, 'errors': 0, 'skipped': 0})
        self.assertEqual(assess(self.root, self.directory)['status'], 'INCOMPLETE')

    def test_source_changes_and_accidental_fixture_promotion_fail_closed(self):
        (self.root / 'skills').mkdir(); (self.root / 'skills/SKILL.md').write_text('changed business rule')
        self.assertIn('source_unchanged', assess(self.root, self.directory)['failed_checks'])
        write(self.directory / 'promotion.json', {'status': 'ready_for_human_review'})
        self.assertIn('fixture_not_qualified', assess(self.root, self.directory)['failed_checks'])

    def test_capability_package_changes_are_part_of_the_source_fingerprint(self):
        (self.root / 'agent-packages').mkdir()
        (self.root / 'agent-packages/research.json').write_text('{"enabled": true}')
        self.assertIn('source_unchanged', assess(self.root, self.directory)['failed_checks'])

    def test_begin_invalidates_the_previous_success_artifact(self):
        write(self.directory / 'acceptance.json', {'status': 'OFFLINE_COMPLETE'})
        with contextlib.redirect_stdout(io.StringIO()): begin(self.root, self.directory)
        self.assertEqual(json.loads((self.directory / 'acceptance.json').read_text())['status'], 'RUNNING')

    def test_modified_package_or_old_package_provenance_cannot_pass(self):
        jar = self.root / 'target/mio-6.0.0.jar'
        with jar.open('ab') as output: output.write(b'changed')
        self.assertIn('packaged_artifact_matches', assess(self.root, self.directory)['failed_checks'])

    def test_missing_release_evidence_cannot_pass(self):
        (self.directory / 'release.json').unlink()
        self.assertIn('packaged_artifact_matches', assess(self.root, self.directory)['failed_checks'])

    def test_old_build_metadata_stays_invalid_even_when_hash_is_recaptured(self):
        jar = self.root / 'target/mio-6.0.0.jar'
        with zipfile.ZipFile(jar, 'w') as output:
            output.writestr('META-INF/build-info.properties',
                            'build.version=6.0.0\nbuild.javaVersion=17\nbuild.sourceRevision=fixture-revision\nbuild.evalRunId=old\nbuild.sourceFingerprint=' + fingerprint(self.root) + '\n')
        from eval.release import artifact
        write(self.directory / 'release.json', artifact(self.root))
        self.assertIn('packaged_artifact_matches', assess(self.root, self.directory)['failed_checks'])
