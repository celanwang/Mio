"""Reproducible offline acceptance. Test infrastructure success never establishes model qualification."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import unittest
import uuid
import xml.etree.ElementTree as ET
from eval.release import revision, matches

PG_SUITES = ('JdbcPostgresIntegrationTest', 'JdbcTaskQueueIntegrationTest', 'JdbcGoalLifecycleIntegrationTest',
             'JdbcMemoryConsistencyIntegrationTest', 'JdbcOrchestrationAcceptanceTest', 'JdbcGoalAcceptanceIntegrationTest',
             'JdbcMemoryJobsIntegrationTest', 'JdbcMemoryUsageIntegrationTest', 'JdbcMaintenanceIntegrationTest',
             'JdbcGoalUsageIntegrationTest')


def fingerprint(root):
    files = [p for folder in ('src', 'scripts', 'eval', 'skills') for p in (root / folder).rglob('*')
             if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc' and p.name != '.DS_Store'] + [root / 'pom.xml']
    digest = hashlib.sha256()
    for path in sorted(files):
        digest.update(path.relative_to(root).as_posix().encode()); digest.update(b'\0')
        digest.update(path.read_bytes()); digest.update(b'\0')
    return digest.hexdigest()


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def read(path):
    return json.loads(path.read_text())


def begin(root, directory):
    run_id = str(uuid.uuid4())
    write(directory / 'acceptance-run.json', {'run_id': run_id, 'source_fingerprint': fingerprint(root), 'source_revision': revision(root)})
    write(directory / 'acceptance.json', {'run_id': run_id, 'status': 'RUNNING', 'qualification': 'NOT_ESTABLISHED'})
    (directory / 'acceptance.md').write_text('# 离线验收尚未完成\n\n本次运行未形成完整结论，不沿用之前的成功报告。\n')
    print(run_id)


def python_tests(root, directory):
    run = read(directory / 'acceptance-run.json')
    suite = unittest.defaultTestLoader.discover(str(root / 'eval/tests'))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    write(directory / 'python-tests.json', {'run_id': run['run_id'], 'tests': result.testsRun,
          'failures': len(result.failures), 'errors': len(result.errors), 'skipped': len(result.skipped)})
    return 0 if result.wasSuccessful() else 1


def assess(root, directory):
    run = read(directory / 'acceptance-run.json'); python = read(directory / 'python-tests.json')
    java = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}; suites = {}; matching = True
    reports = list((root / 'target/surefire-reports').glob('TEST-*.xml'))
    for report in reports:
        suite = ET.parse(report).getroot()
        properties = {p.get('name'): p.get('value') for p in suite.findall('properties/property')}
        matching = matching and properties.get('mio.eval.run-id') == run['run_id']
        values = {key: int(suite.get(key, '0')) for key in java}
        for key in java: java[key] += values[key]
        suites[suite.get('name', '').split('.')[-1]] = values
    fixture = read(directory / 'promotion.json')
    pg_complete = all(name in suites and suites[name]['tests'] > 0
                      and suites[name]['skipped'] == 0 for name in PG_SUITES)
    checks = {
        'current_run_only': bool(reports) and matching and python['run_id'] == run['run_id'],
        'source_unchanged': fingerprint(root) == run['source_fingerprint'],
        'java_passed': java['tests'] > 0 and java['failures'] == java['errors'] == 0,
        'python_passed': python['tests'] > 0 and python['failures'] == python['errors'] == python['skipped'] == 0,
        'postgres_executed': pg_complete and java['skipped'] == 0,
        'fixture_not_qualified': fixture['status'] == 'insufficient_or_failed_evidence',
        'packaged_artifact_matches': matches(root, directory, run),
    }
    failed = [name for name, passed in checks.items() if not passed]
    status = 'OFFLINE_COMPLETE' if not failed else 'POSTGRES_REQUIRED' if failed == ['postgres_executed'] else 'INCOMPLETE'
    return {'schema_version': 1, 'run_id': run['run_id'], 'source_fingerprint': run['source_fingerprint'],
            'status': status, 'qualification': 'NOT_ESTABLISHED', 'java': java, 'python': python,
            'source_revision': run.get('source_revision'),
            'release': read(directory / 'release.json') if (directory / 'release.json').exists() else None,
            'checks': checks, 'failed_checks': failed,
            'scope': 'Multi-Agent v6 controlled foreground preview; no real model/provider qualification'}


def report(root, directory, require_postgres):
    result = assess(root, directory); write(directory / 'acceptance.json', result)
    lines = ['# Multi-Agent v6 受控预览验收', '', '状态：`' + result['status'] + '`', '',
             '目标：可暂停、可恢复、可关闭，保留预算与未知操作，具备可重复的离线验收。', '',
             'Java：{tests} 项，失败 {failures}，错误 {errors}，跳过 {skipped}。'.format(**result['java']),
             'Python：{tests} 项，失败 {failures}，错误 {errors}，跳过 {skipped}。'.format(**result['python']), '',
             '生产资格：未建立。本报告不包含真实模型采样或真实供应商操作；业务任务仍由前台显式消费，记忆后台单独验收。', '',
             '同一运行编号与源码指纹用于拒绝混入旧报告。源码指纹：`' + result['source_fingerprint'] + '`。', '']
    lines.extend('- ' + name + '：' + ('通过' if passed else '未通过') for name, passed in result['checks'].items())
    if result['release']:
        package = result['release']
        lines.extend(['', '构建版本：`' + str(package.get('version')) + '`。Git revision：`' + str(result['source_revision']) + '`。',
                      '产物：`' + str(package.get('file')) + '`。SHA-256：`' + str(package.get('sha256')) + '`。'])
    (directory / 'acceptance.md').write_text('\n'.join(lines) + '\n')
    return 0 if result['status'] == 'OFFLINE_COMPLETE' or result['status'] == 'POSTGRES_REQUIRED' and not require_postgres else 1


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('action', choices=('begin', 'python', 'report'))
    parser.add_argument('--directory', default='target/evaluation'); parser.add_argument('--require-postgres', action='store_true')
    args = parser.parse_args(); root = Path(__file__).resolve().parent.parent; directory = root / args.directory
    if args.action == 'begin': begin(root, directory); return 0
    if args.action == 'python': return python_tests(root, directory)
    return report(root, directory, args.require_postgres)


if __name__ == '__main__':
    sys.exit(main())
