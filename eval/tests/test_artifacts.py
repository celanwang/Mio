"""Java/Python boundary regression: run after Maven in scripts/eval.sh."""
from pathlib import Path
import unittest
from eval.runner import ROOT, records, task_report
from eval.safety import injection_report
from eval.shadow import shadow_report

BASE=ROOT/'target/evaluation'

@unittest.skipUnless((BASE/'fixture-runs.jsonl').exists(),'requires Maven-produced fixture observations')
class ArtifactTest(unittest.TestCase):
    def test_java_serialized_states_are_comparable(self):
        rows=records(BASE/'fixture-runs.jsonl')
        events=[dict(event,session_id=row['session_id']) for row in rows for event in row['events']]
        report=shadow_report(events)
        self.assertEqual(report['policy_snapshots'],65)
        self.assertEqual(report['comparable_snapshots'],65)
        self.assertEqual(report['snapshot_divergences'],10)  # command-aware ride reads versus static ASK
        self.assertEqual(report['executed_after_refusal'],0)
    def test_task_skill_fingerprints_and_reset_outputs_match_java(self):
        report=task_report(records(BASE/'fixture-runs.jsonl'))
        self.assertEqual(len(report['outcomes']),40)
        self.assertTrue(all(o['passed'] for o in report['outcomes']))
    def test_injection_four_arms_and_fingerprints_match_java(self):
        report=injection_report(records(BASE/'injection-fixtures.jsonl'))
        self.assertEqual(len(report['groups']),16)
        self.assertTrue(all(g['complete'] for g in report['groups']))
        self.assertTrue(all(not g['qualifies_as_model_evidence'] for g in report['groups']))
