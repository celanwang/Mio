import copy
import unittest
from eval.coverage import coverage_report
from eval.promotion import promotion_review
from eval.safety import matrix_plan, injection_report
from eval.runner import corpus, make_report, DATA, ROOT, digest
from eval.shadow import shadow_report
from test_evaluation import observation, task_rows


def matrix_rows():
    case=next(iter(corpus('injections').values()))
    return [observation('injections',case_id=case['id'],sample_id=str(i),injected=inj,delimiters=wrapped,
                        unsafe_effect=inj and not wrapped,unsafe_attempt=inj,task_success=not inj or wrapped)
            for i in range(5) for inj in (False,True) for wrapped in (False,True)]


class SafetyTest(unittest.TestCase):
    def test_plan_has_all_four_arms_for_every_case_and_sample(self):
        plan=matrix_plan()
        self.assertEqual(len(plan),len(corpus('injections'))*4*5)
        for case in corpus('injections'):
            self.assertEqual(sum(p['case_id']==case for p in plan),20)
        with self.assertRaises(ValueError):matrix_plan(4)
    def test_asr_increment_and_difference_in_differences(self):
        report=injection_report(matrix_rows()); group=report['groups'][0]
        self.assertTrue(group['complete']); self.assertFalse(group['qualifies_as_model_evidence'])
        self.assertEqual(group['stripped_asr_increment'],1)
        self.assertEqual(group['difference_in_differences'],1)
    def test_missing_or_unpaired_arms_cannot_measure_effectiveness(self):
        rows=matrix_rows(); rows.pop()
        group=injection_report(rows)['groups'][0]
        self.assertFalse(group['complete']);self.assertIsNone(group['stripped_asr_increment'])
        rows=matrix_rows(); rows[0]['sample_id']='unpaired'
        self.assertFalse(injection_report(rows)['groups'][0]['complete'])
    def test_duplicate_and_truthy_string_rejected(self):
        rows=matrix_rows(); rows.append(rows[0])
        with self.assertRaises(ValueError):injection_report(rows)
        rows=matrix_rows();rows[0]['unsafe_effect']='false'
        with self.assertRaises(ValueError):injection_report(rows)
    def test_successful_scripted_fixture_cannot_promote(self):
        report=make_report(task_rows())
        decision=promotion_review(report,injection_report([]),shadow_report([]))
        self.assertEqual(decision['status'],'insufficient_or_failed_evidence')
        self.assertFalse(decision['production_changed'])
        self.assertTrue(any(c['name'].startswith('model_samples/') and not c['passed'] for c in decision['checks']))
        self.assertTrue(any(c['name']=='judge_human_agreement' and not c['passed'] for c in decision['checks']))
    def test_promotion_fails_even_if_config_reviewed_but_evidence_absent(self):
        from eval.runner import read_json
        config=read_json(ROOT/'eval/gates.json');config['approved_for_promotion']=True
        decision=promotion_review(make_report([]),injection_report([]),shadow_report([]),config)
        self.assertEqual(decision['status'],'insufficient_or_failed_evidence')
    def test_matrix_execution_errors_do_not_qualify_as_resistance(self):
        rows=matrix_rows();rows[0]['error']='timeout'
        report=injection_report(rows)
        self.assertFalse(report['groups'][0]['complete'])
        self.assertIsNone(report['groups'][0]['stripped_asr_increment'])
    def test_review_can_only_be_ready_with_all_explicit_evidence(self):
        from unittest.mock import patch
        from eval.runner import read_json
        config=read_json(ROOT/'eval/gates.json');config['approved_for_promotion']=True
        report=make_report([])
        report['tasks']={'groups':[dict(task_id='unit-fixture',model='m',route='standard',trials=5,
                    qualifies_as_model_evidence=True,pass_power_3=1)],'missing_tasks':[]}
        report['evaluation_targets']=['production-agent']
        for name in ('reviews','memory','habits','judge'):
            report['evidence_sources'][name]=['model'];report[name]['count']=20;report[name]['missing_ids']=[]
        report['gold_labels_reviewed']={k:True for k in ('reviews','memory','habits')}
        report['reviews']['by_risk']['irreversible']['unsafe_block_recall']=1
        report['reviews']['by_risk']['reversible'].update(auto_allow_precision=1,false_block_rate=0)
        report['memory'].update(evidence_recall=1,answer_accuracy=1,obsolete_recall_rate=0)
        report['habits']['false_positive_rate']=0
        report['judge'].update(pending_human_label_ids=[],labeled_count=20,human_agreement=1,order_stability=1)
        injection={'groups':[dict(qualifies_as_model_evidence=True,arms=[dict(injected=False,delimiters=True,
                    count=5,task_success_rate=1,attack_success_rate=0)])],'missing_cases':[]}
        shadow=dict(comparable_snapshots=100,snapshot_divergence_rate=0,executed_after_refusal=0,
                    diagnostics={},unmatched_results=0,unresolved_calls=0)
        with patch('eval.coverage.coverage_report',return_value=dict(fully_reviewed=True,pending_semantic_rules=[])):
            result=promotion_review(report,injection,shadow,config)
            self.assertEqual(result['status'],'ready_for_human_review');self.assertFalse(result['production_changed'])
            report['reviews']['by_risk']['irreversible']['unsafe_block_recall']=.99
            self.assertEqual(promotion_review(report,injection,shadow,config)['status'],'insufficient_or_failed_evidence')
    def test_rule_registry_points_to_real_source_assertions_and_pairs(self):
        report=coverage_report()
        self.assertEqual(report['rule_count'],16)
        self.assertTrue(report['pending_semantic_rules']);self.assertFalse(report['fully_reviewed'])


if __name__=='__main__':unittest.main()
