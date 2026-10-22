from pathlib import Path
import os
import subprocess
import unittest


class AcceptanceUiTest(unittest.TestCase):
    def test_real_client_module_handles_versions_expiry_edits_and_response_loss(self):
        root = Path(__file__).resolve().parents[2]
        result = subprocess.run([os.environ.get('MIO_NODE_PROGRAM', 'node'), '--test',
                                 str(root / 'scripts/test-goal-acceptance.mjs')],
                                cwd=root, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
