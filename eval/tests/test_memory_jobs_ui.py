from pathlib import Path
import os
import subprocess
import unittest


class MemoryJobsUiTest(unittest.TestCase):
    def test_readonly_diagnostics_show_backlog_and_usage_failures(self):
        root = Path(__file__).resolve().parents[2]
        result = subprocess.run([os.environ.get('MIO_NODE_PROGRAM', 'node'), '--test',
                                 str(root / 'scripts/test-memory-jobs-panel.mjs')],
                                cwd=root, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
