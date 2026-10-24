import pathlib
import subprocess
import unittest


class WorkspaceUiTest(unittest.TestCase):
    def test_workspace_evidence_actions_and_session_switch(self):
        root = pathlib.Path(__file__).resolve().parents[2]
        result = subprocess.run(["node", "--test", "scripts/test-goal-workspace.mjs"], cwd=root,
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_personal_correction_selection_approval_and_event_scope(self):
        root = pathlib.Path(__file__).resolve().parents[2]
        result = subprocess.run(["node", "--test", "scripts/test-personal-outcomes.mjs"], cwd=root,
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
